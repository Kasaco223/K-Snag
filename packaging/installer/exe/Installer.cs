using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Net;
using System.Reflection;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;

namespace KSnagInstaller
{
    /// <summary>Datos de la versión, incrustados al compilar (packaging/build.ps1 → AssemblyMetadata).</summary>
    internal static class BuildInfo
    {
        public const string App = "K-Snag";
        public const string Web = "https://ksnagapp.vercel.app";

        public static readonly string Version = Meta("KdVersion");
        public static readonly string Url = Meta("KdBundleUrl");
        public static readonly string Sha256 = Meta("KdBundleSha256");
        public static readonly string Name = Meta("KdBundleName");
        public static readonly string Size = Meta("KdBundleSize");

        public static bool IsTemplate
        {
            get { return Version.Contains("{{") || Url.Contains("{{") || Sha256.Contains("{{") || Name.Contains("{{"); }
        }

        public static long SizeBytes
        {
            get
            {
                long size;
                return long.TryParse(Size, NumberStyles.Integer, CultureInfo.InvariantCulture, out size) ? size : 0;
            }
        }

        private static string Meta(string key)
        {
            foreach (var a in typeof(BuildInfo).Assembly.GetCustomAttributes<AssemblyMetadataAttribute>())
            {
                if (a.Key == key) return a.Value ?? "";
            }
            return "";
        }
    }

    /// <summary>Error que se le muestra a la persona: Message en pantalla, Detail como ayuda o dato técnico.</summary>
    internal sealed class KdException : Exception
    {
        public KdException(string message, string detail) : base(message)
        {
            Detail = detail ?? "";
        }

        public string Detail { get; private set; }
    }

    internal sealed class InstallCanceledException : Exception
    {
        public InstallCanceledException() : base("Instalación cancelada") { }
    }

    internal static class Log
    {
        public static readonly string File = Path.Combine(Path.GetTempPath(), "K-Snag-instalacion.log");

        public static void Write(string message)
        {
            try
            {
                string line = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss", CultureInfo.InvariantCulture) + " " + message + Environment.NewLine;
                System.IO.File.AppendAllText(File, line, Encoding.UTF8);
            }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
        }
    }

    internal sealed class Options
    {
        public string Local;
        public string Root;
        public bool ExplicitRoot;
        public bool NoRegistry;
        public string Mode = "ui"; // ui, console, quiet
        public bool? Desktop;
        public string ShortcutDir;
        public string Sha256 = BuildInfo.Sha256;
        public bool? Launch;
        public bool Help;
        public string RawArgs = "";

        private static readonly string[] NeedsValue = { "--local", "--root", "--shortcut-dir", "--sha256" };

        /// <summary>Mismas opciones que Instalar-K-Snag.cmd. Lanza KdException si alguna no vale.</summary>
        public static Options Parse(string[] argv)
        {
            var o = new Options();
            o.RawArgs = string.Join(" ", argv.Select(a => a.IndexOf(' ') >= 0 ? "\"" + a + "\"" : a));
            // El modo se decide antes que nada: un error en otra opción no debe abrir ventanas con --quiet.
            foreach (string a in argv)
            {
                if (a.Equals("--quiet", StringComparison.OrdinalIgnoreCase)) o.Mode = "quiet";
                else if (a.Equals("--console", StringComparison.OrdinalIgnoreCase) && o.Mode != "quiet") o.Mode = "console";
            }
            for (int i = 0; i < argv.Length; i++)
            {
                string a = argv[i];
                string key = a.ToLowerInvariant();
                string value = null;
                if (NeedsValue.Contains(key))
                {
                    if (i + 1 >= argv.Length) throw new KdException("Falta el valor de la opción " + a, "");
                    value = argv[++i];
                }
                switch (key)
                {
                    case "--local": o.Local = FullPath(value); break;
                    case "--root": o.Root = FullPath(value); o.ExplicitRoot = true; break;
                    case "--shortcut-dir": o.ShortcutDir = FullPath(value); break;
                    case "--sha256": o.Sha256 = value.Trim().ToLowerInvariant(); break;
                    case "--no-registry": o.NoRegistry = true; break;
                    case "--desktop": o.Desktop = true; break;
                    case "--no-desktop": o.Desktop = false; break;
                    case "--quiet": o.Mode = "quiet"; break;
                    case "--console": if (o.Mode != "quiet") o.Mode = "console"; break;
                    case "--launch": o.Launch = true; break;
                    case "--no-launch": o.Launch = false; break;
                    case "--help":
                    case "-h":
                    case "/?":
                        o.Help = true; break;
                    default: throw new KdException("Opción desconocida: " + a, "");
                }
            }
            if (!o.Help && !Regex.IsMatch(o.Sha256 ?? "", "^[0-9a-fA-F]{64}$") && !BuildInfo.IsTemplate)
            {
                throw new KdException("El hash SHA-256 no es válido.", o.Sha256);
            }
            return o;
        }

        private static string FullPath(string path)
        {
            try
            {
                return Path.GetFullPath(path);
            }
            catch (Exception e) when (e is ArgumentException || e is NotSupportedException || e is PathTooLongException)
            {
                throw new KdException("La ruta no es válida: " + path, e.Message);
            }
        }

        public static string HelpText()
        {
            return string.Join(Environment.NewLine, new[]
            {
                "",
                "Instalador de K-Snag " + BuildInfo.Version,
                "",
                "  Instalar-K-Snag.exe [opciones]",
                "",
                "  --local <zip>          usa un paquete local en vez de descargarlo",
                "  --sha256 <hash>        SHA-256 esperado del paquete (por defecto, el de esta versión)",
                "  --root <carpeta>       instala en otra carpeta (pruebas)",
                "  --no-registry          no toca el registro ni crea accesos directos (pruebas)",
                "  --shortcut-dir <dir>   crea el acceso directo del menú Inicio en esa carpeta",
                "  --desktop/--no-desktop crea (o no) el acceso directo en el escritorio",
                "  --quiet                sin ventanas ni preguntas (CI); no abre la app salvo --launch",
                "  --console              muestra el progreso en la consola en vez de la ventana",
                "  --launch/--no-launch   abre (o no) K-Snag al terminar",
                "",
                "  Códigos de salida: 0 instalado, 1 error o cancelado.",
                "",
            });
        }
    }

    internal sealed class Step
    {
        public string Text;
        public int Weight;
        public bool Critical;
        public Action Run;
    }

    /// <summary>
    /// Los pasos de la instalación (los mismos del .cmd). Corren en un hilo aparte; la ventana o la consola
    /// leen Progress, Busy, Detail y StepIndex.
    /// </summary>
    internal sealed class Engine
    {
        // La carpeta conserva el nombre anterior de la app (constants.DIR_NAME): las instalaciones existentes viven ahí.
        public const string DirName = "K-Downloader";
        private const string DefaultRootRelative = @"Programs\K-Downloader";
        private const long MB = 1024 * 1024;

        private readonly Options _opt;
        private readonly object _lock = new object();
        private volatile bool _cancel;
        private string _detail = "";
        private string _tempDir;
        private string _staging;
        private string _zip;
        private bool _downloaded;
        private volatile HttpWebRequest _request;
        private double _stepFraction = -1;

        public Engine(Options opt)
        {
            _opt = opt;
            string root = opt.Root ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), DefaultRootRelative);
            Root = root.TrimEnd('\\');
            string cur = Path.Combine(Root, "current");
            if (File.Exists(cur))
            {
                try { Existing = File.ReadAllText(cur).Trim(); } catch (IOException) { } catch (UnauthorizedAccessException) { }
            }
            Steps = new List<Step>
            {
                new Step { Text = "Comprobando tu sistema…", Weight = 2, Run = StepCheck },
                new Step { Text = "Descargando K-Snag…", Weight = 60, Run = StepDownload },
                new Step { Text = "Verificando el archivo…", Weight = 3, Run = StepVerify },
                new Step { Text = "Revisando si K-Snag está abierto…", Weight = 1, Run = StepClose },
                new Step { Text = "Extrayendo archivos…", Weight = 18, Run = StepExtract },
                new Step { Text = "Copiando archivos…", Weight = 8, Critical = true, Run = StepPlace },
                new Step { Text = "Creando accesos directos…", Weight = 6, Critical = true, Run = StepFinish },
                new Step { Text = "Limpiando…", Weight = 2, Run = StepCleanup },
            };
        }

        public string Root { get; private set; }
        public string Existing { get; private set; }
        public List<Step> Steps { get; private set; }
        public bool Desktop { get; set; }
        public volatile int StepIndex;
        public volatile bool Busy;

        /// <summary>Pregunta sí/no. La pone quien muestra el instalador (ventana o consola).</summary>
        public Func<string, bool> Confirm = message => true;

        public bool CancelRequested { get { return _cancel; } }

        /// <summary>Avance del paso actual de 0 a 1, o -1 si no se puede medir.</summary>
        public double StepFraction { get { return _stepFraction; } }

        public bool InCritical
        {
            get
            {
                int i = StepIndex;
                return i >= 0 && i < Steps.Count && Steps[i].Critical;
            }
        }

        public string Detail
        {
            get { lock (_lock) return _detail; }
            private set { lock (_lock) _detail = value ?? ""; }
        }

        public string DesktopLink
        {
            get { return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory), "K-Snag.lnk"); }
        }

        public bool DefaultDesktop
        {
            get { return Existing == null || File.Exists(DesktopLink); }
        }

        public void RequestCancel()
        {
            _cancel = true;
            HttpWebRequest req = _request;
            if (req != null) { try { req.Abort(); } catch (Exception) { } }
        }

        private void ThrowIfCanceled()
        {
            if (_cancel) throw new InstallCanceledException();
        }

        private int StepWeight(int index)
        {
            if (index == 1 && _opt.Local != null) return 0;
            return Steps[index].Weight;
        }

        /// <summary>Progreso total de 0 a 1 (la descarga pesa 0 con --local).</summary>
        public double Progress
        {
            get
            {
                double total = 0, done = 0;
                int current = StepIndex;
                for (int i = 0; i < Steps.Count; i++)
                {
                    int w = StepWeight(i);
                    total += w;
                    if (i < current) done += w;
                }
                double f = _stepFraction;
                if (current < Steps.Count && f >= 0) done += StepWeight(current) * Math.Max(0.0, Math.Min(1.0, f));
                return total <= 0 ? 1.0 : done / total;
            }
        }

        /// <summary>Corre todos los pasos. onStep se llama al empezar cada uno (en este mismo hilo).</summary>
        public void Run(Action<int> onStep)
        {
            Log.Write("--- Instalador " + BuildInfo.Version + " (" + _opt.Mode + ") en " + Root + " args: " + _opt.RawArgs);
            for (int i = 0; i < Steps.Count; i++)
            {
                ThrowIfCanceled();
                _stepFraction = -1;
                Busy = true;
                Detail = "";
                StepIndex = i;
                if (onStep != null) onStep(i);
                Steps[i].Run();
            }
            StepIndex = Steps.Count;
            _stepFraction = -1;
            Busy = false;
        }

        // --- Utilidades ------------------------------------------------------------------------

        public static string Quote(string path)
        {
            string p = path;
            if (p.Length > 3) p = p.TrimEnd('\\');
            return "\"" + p + "\"";
        }

        private static string NewId()
        {
            return Guid.NewGuid().ToString("N").Substring(0, 8);
        }

        public static bool IsWithin(string child, string parent)
        {
            string c = Path.GetFullPath(child).TrimEnd('\\');
            string p = Path.GetFullPath(parent).TrimEnd('\\');
            return c.Equals(p, StringComparison.OrdinalIgnoreCase) || c.StartsWith(p + "\\", StringComparison.OrdinalIgnoreCase);
        }

        private static bool IsReparse(string path)
        {
            try
            {
                return (File.GetAttributes(path) & FileAttributes.ReparsePoint) != 0;
            }
            catch (IOException) { return false; }
            catch (UnauthorizedAccessException) { return false; }
        }

        private static void RemoveDir(string path)
        {
            // .NET Framework no sigue uniones (junctions) ni enlaces al borrar: quita solo el enlace.
            if (string.IsNullOrEmpty(path) || !Directory.Exists(path)) return;
            try
            {
                if (IsReparse(path)) Directory.Delete(path, false); else Directory.Delete(path, true);
            }
            catch (Exception e) when (e is IOException || e is UnauthorizedAccessException)
            {
                Log.Write("No se pudo borrar " + path + ": " + e.Message);
            }
        }

        private static void MoveDir(string from, string to)
        {
            for (int i = 0; ; i++)
            {
                try
                {
                    Directory.Move(from, to);
                    return;
                }
                catch (Exception e) when ((e is IOException || e is UnauthorizedAccessException) && i < 9)
                {
                    Thread.Sleep(500);
                }
            }
        }

        public static bool IsElevated()
        {
            try
            {
                return new WindowsPrincipal(WindowsIdentity.GetCurrent()).IsInRole(WindowsBuiltInRole.Administrator);
            }
            catch (Exception)
            {
                return false;
            }
        }

        private List<Process> RunningApp()
        {
            string rt = Path.Combine(Root, "runtime");
            var found = new List<Process>();
            foreach (string name in new[] { "python", "pythonw" })
            {
                foreach (Process p in Process.GetProcessesByName(name))
                {
                    string path = null;
                    try { path = Native.ProcessPath(p.Id); } catch (Exception) { }
                    if (path != null && IsWithin(path, rt)) found.Add(p); else p.Dispose();
                }
            }
            return found;
        }

        public void CleanTemp()
        {
            if (_staging != null) { RemoveDir(_staging); _staging = null; }
            if (_tempDir != null) { RemoveDir(_tempDir); _tempDir = null; }
        }

        public void StartApp()
        {
            string pyw = Path.Combine(Root, @"runtime\pythonw.exe");
            if (IsElevated())
            {
                // Con "Ejecutar como administrador" la app no debe quedar elevada: no recibiría enlaces arrastrados y
                // una segunda ventana normal no podría hablarle. explorer.exe abre el acceso directo con los permisos
                // normales de la sesión.
                string dir = _opt.ShortcutDir ?? Environment.GetFolderPath(Environment.SpecialFolder.Programs);
                string lnk = Path.Combine(dir, "K-Snag.lnk");
                if (File.Exists(lnk))
                {
                    Log.Write("Instalador como administrador: abriendo " + lnk + " con explorer.exe");
                    Process.Start(new ProcessStartInfo(Path.Combine(Environment.GetEnvironmentVariable("SystemRoot") ?? @"C:\Windows", "explorer.exe"), Quote(lnk)) { UseShellExecute = true });
                }
                else
                {
                    Log.Write("Instalador como administrador y sin acceso directo: no se abre la app para no dejarla elevada");
                }
                return;
            }
            Log.Write("Abriendo " + pyw);
            Process.Start(new ProcessStartInfo(pyw, Quote(Path.Combine(Root, "launch.pyw")))
            {
                UseShellExecute = true,
                WorkingDirectory = Root,
            });
        }

        // --- Pasos -----------------------------------------------------------------------------

        private void StepCheck()
        {
            Version v = Environment.OSVersion.Version;
            if (v.Major < 10) throw new KdException("K-Snag necesita Windows 10 u 11.", "Windows " + v);
            if (!Environment.Is64BitOperatingSystem) throw new KdException("K-Snag necesita Windows de 64 bits.", "");
            if (BuildInfo.IsTemplate) throw new KdException("Este instalador es una plantilla sin procesar; usa el que se publica en cada versión.", "");
            if (IsElevated() && !_opt.ExplicitRoot)
            {
                // "Ejecutar como administrador" desde una cuenta sin permisos pide otra cuenta, y entonces se
                // instalaría en el perfil de esa otra cuenta, no en el de quien está usando la PC.
                string me = WindowsIdentity.GetCurrent().Name;
                string shell = null;
                try { shell = Native.SessionUser(Process.GetCurrentProcess().SessionId); }
                catch (Exception e) { Log.Write("No se pudo saber quién usa la sesión: " + e.Message); }
                Log.Write("Instalador como administrador: " + me + " | sesión: " + (shell ?? ""));
                if (!string.IsNullOrEmpty(shell) && !shell.Equals(me, StringComparison.OrdinalIgnoreCase))
                {
                    throw new KdException(
                        "Lo abriste como administrador con otra cuenta (" + me + "), así que K-Snag se instalaría para esa cuenta y no para la tuya.",
                        "Ciérralo y ábrelo con doble clic, sin \"Ejecutar como administrador\".");
                }
            }
            if (_opt.Local != null && !File.Exists(_opt.Local))
            {
                throw new KdException("No se encontró el paquete indicado con --local.", _opt.Local);
            }

            string root = Root;
            string driveRoot = Path.GetPathRoot(root);
            if (string.IsNullOrEmpty(driveRoot) || driveRoot.TrimEnd('\\').Equals(root, StringComparison.OrdinalIgnoreCase))
            {
                throw new KdException("La carpeta de instalación no es válida.", root);
            }
            if (!_opt.ExplicitRoot && !Path.GetFileName(root).Equals(DirName, StringComparison.OrdinalIgnoreCase))
            {
                throw new KdException("La carpeta de instalación no es válida.", root);
            }
            if (File.Exists(root)) throw new KdException("Ya existe un archivo con el nombre de la carpeta de instalación.", root);
            if (Directory.Exists(root))
            {
                if (IsReparse(root)) throw new KdException("La carpeta de instalación es un enlace; por seguridad no se usa.", root);
                bool hasItems = Directory.EnumerateFileSystemEntries(root).Any();
                if (hasItems && !File.Exists(Path.Combine(root, "launch.pyw")))
                {
                    throw new KdException("La carpeta de instalación ya existe y no parece de K-Snag; por seguridad no se toca.", root);
                }
            }

            long size = BuildInfo.SizeBytes;
            long need = Math.Max(size, 30 * MB) * 4 + 64 * MB;
            long free = long.MaxValue;
            try { free = new DriveInfo(driveRoot).AvailableFreeSpace; } catch (Exception) { }
            if (free < need)
            {
                throw new KdException("No hay espacio suficiente en el disco " + driveRoot + ". Libera al menos " +
                    (long)Math.Ceiling(need / (double)MB) + " MB y vuelve a intentarlo.", "");
            }

            // Restos de un intento anterior que se haya cortado.
            string parent = Path.GetDirectoryName(root);
            string leaf = Path.GetFileName(root);
            if (Directory.Exists(parent))
            {
                foreach (string d in SafeDirs(parent, leaf + ".staging-*")) RemoveDir(d);
            }
            if (Directory.Exists(root))
            {
                foreach (string d in SafeDirs(root, "runtime.old-*")) RemoveDir(d);
                string apps = Path.Combine(root, "app");
                if (Directory.Exists(apps))
                {
                    foreach (string d in SafeDirs(apps, "*.old-*")) RemoveDir(d);
                }
            }
        }

        private static IEnumerable<string> SafeDirs(string dir, string pattern)
        {
            try
            {
                return Directory.GetDirectories(dir, pattern);
            }
            catch (Exception e) when (e is IOException || e is UnauthorizedAccessException)
            {
                return new string[0];
            }
        }

        private void StepDownload()
        {
            if (_opt.Local != null)
            {
                _zip = _opt.Local;
                _downloaded = false;
                return;
            }
            _tempDir = Path.Combine(Path.GetTempPath(), "K-Snag-" + NewId());
            Directory.CreateDirectory(_tempDir);
            _zip = Path.Combine(_tempDir, BuildInfo.Name);
            _downloaded = true;
            Busy = false;
            _stepFraction = 0;

            ServicePointManager.SecurityProtocol |= SecurityProtocolType.Tls12 | (SecurityProtocolType)12288; // TLS 1.3
            Exception last = null;
            for (int attempt = 0; attempt < 4; attempt++)
            {
                ThrowIfCanceled();
                if (attempt > 0)
                {
                    Log.Write("Reintentando la descarga (" + attempt + "): " + (last != null ? last.Message : ""));
                    Thread.Sleep(2000);
                }
                try
                {
                    DownloadOnce();
                    return;
                }
                catch (InstallCanceledException) { throw; }
                catch (KdException) { throw; }
                catch (Exception e) when (e is WebException || e is IOException)
                {
                    ThrowIfCanceled();
                    last = e;
                }
                finally
                {
                    _request = null;
                }
            }
            throw new KdException("No se pudo descargar K-Snag. Revisa tu conexión a internet y vuelve a intentarlo.",
                last != null ? last.Message : "");
        }

        private void DownloadOnce()
        {
            var req = (HttpWebRequest)WebRequest.Create(BuildInfo.Url);
            req.UserAgent = "K-Snag-Installer/" + BuildInfo.Version + " (+" + BuildInfo.Web + ")";
            req.AllowAutoRedirect = true;
            req.Timeout = 30000;
            req.ReadWriteTimeout = 60000;
            _request = req;
            ThrowIfCanceled();
            Log.Write("> GET " + BuildInfo.Url);
            using (var resp = (HttpWebResponse)req.GetResponse())
            {
                if (resp.ResponseUri.Scheme != Uri.UriSchemeHttps)
                {
                    throw new KdException("No se pudo descargar K-Snag de forma segura.", "redirección a " + resp.ResponseUri);
                }
                long total = resp.ContentLength > 0 ? resp.ContentLength : BuildInfo.SizeBytes;
                using (Stream input = resp.GetResponseStream())
                using (var output = new FileStream(_zip, FileMode.Create, FileAccess.Write, FileShare.None, 1 << 16))
                {
                    var buffer = new byte[1 << 16];
                    long done = 0;
                    int n;
                    while ((n = input.Read(buffer, 0, buffer.Length)) > 0)
                    {
                        ThrowIfCanceled();
                        output.Write(buffer, 0, n);
                        done += n;
                        if (total > 0)
                        {
                            _stepFraction = Math.Min(1.0, done / (double)total);
                            Detail = string.Format(CultureInfo.CurrentCulture, "{0:N1} MB de {1:N1} MB", done / (double)MB, total / (double)MB);
                        }
                    }
                }
            }
        }

        private static string Sha256Of(string path)
        {
            using (var sha = SHA256.Create())
            using (var f = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read, 1 << 16))
            {
                return BitConverter.ToString(sha.ComputeHash(f)).Replace("-", "").ToLowerInvariant();
            }
        }

        private void StepVerify()
        {
            string hash = Sha256Of(_zip);
            string expected = _opt.Sha256.ToLowerInvariant();
            Log.Write("SHA-256 " + _zip + " = " + hash);
            if (hash != expected)
            {
                if (_downloaded) { try { File.Delete(_zip); } catch (Exception) { } }
                Log.Write("SHA-256 esperado " + expected + ", obtenido " + hash);
                throw new KdException(
                    "El archivo descargado no pasó la verificación de seguridad (SHA-256): puede estar dañado o haber sido modificado. No se instaló nada.",
                    "Esperado " + expected.Substring(0, 16) + "…, obtenido " + hash.Substring(0, 16) + "…");
            }
        }

        private void StepClose()
        {
            List<Process> procs = RunningApp();
            if (procs.Count == 0) return;
            if (!Confirm("K-Snag está abierto. Para continuar hay que cerrarlo y las descargas en curso se detendrán. ¿Cerrarlo ahora?"))
            {
                throw new KdException("Instalación cancelada: K-Snag sigue abierto.", "");
            }
            Log.Write("Cerrando " + procs.Count + " proceso(s) de K-Snag");
            foreach (Process p in procs)
            {
                try { p.Kill(); } catch (Exception) { }
            }
            foreach (Process p in procs)
            {
                try { p.WaitForExit(15000); } catch (Exception) { }
                p.Dispose();
            }
            List<Process> still = RunningApp();
            int left = still.Count;
            foreach (Process p in still) p.Dispose();
            if (left > 0) throw new KdException("No se pudo cerrar K-Snag. Ciérralo y vuelve a intentarlo.", "");
        }

        private void CheckStaging()
        {
            string s = _staging;
            string[] need =
            {
                "launch.pyw", "current", "uninstall.cmd", @"runtime\python.exe", @"runtime\pythonw.exe",
                @"app\" + BuildInfo.Version + @"\kdownloader\__init__.py",
            };
            foreach (string rel in need)
            {
                string p = Path.Combine(s, rel);
                if (!File.Exists(p) && !Directory.Exists(p)) throw new KdException("El paquete está incompleto.", "falta " + rel);
            }
            string cur = File.ReadAllText(Path.Combine(s, "current")).Trim();
            if (cur != BuildInfo.Version) throw new KdException("El paquete no corresponde a esta versión.", "current = " + cur);
        }

        private void StepExtract()
        {
            string parent = Path.GetDirectoryName(Root);
            Directory.CreateDirectory(parent);
            _staging = Path.Combine(parent, Path.GetFileName(Root) + ".staging-" + NewId());
            Directory.CreateDirectory(_staging);
            try
            {
                using (ZipArchive zip = ZipFile.OpenRead(_zip))
                {
                    string stagingFull = Path.GetFullPath(_staging).TrimEnd('\\') + "\\";
                    int count = zip.Entries.Count;
                    int i = 0;
                    Busy = false;
                    foreach (ZipArchiveEntry entry in zip.Entries)
                    {
                        ThrowIfCanceled();
                        string dest = Path.GetFullPath(Path.Combine(_staging, entry.FullName));
                        // Ninguna entrada puede salir de la carpeta de destino (zip slip).
                        if (!dest.StartsWith(stagingFull, StringComparison.OrdinalIgnoreCase))
                        {
                            throw new KdException("No se pudo extraer el paquete.", "ruta fuera de la carpeta: " + entry.FullName);
                        }
                        if (entry.FullName.EndsWith("/") || entry.FullName.EndsWith("\\"))
                        {
                            Directory.CreateDirectory(dest);
                        }
                        else
                        {
                            Directory.CreateDirectory(Path.GetDirectoryName(dest));
                            entry.ExtractToFile(dest, false);
                        }
                        i++;
                        _stepFraction = count > 0 ? i / (double)count : 1.0;
                    }
                }
            }
            catch (Exception e) when (e is IOException || e is InvalidDataException || e is UnauthorizedAccessException || e is NotSupportedException)
            {
                throw new KdException("No se pudo extraer el paquete.", e.Message);
            }
            CheckStaging();
        }

        private void StepPlace()
        {
            string root = Root;
            string stg = _staging;
            string ver = BuildInfo.Version;
            if (!Directory.Exists(root))
            {
                MoveDir(stg, root);
                _staging = null;
                return;
            }
            string id = NewId();

            // 1. Runtime: se renombra el actual (se recupera si algo falla) y se pone el nuevo.
            string rt = Path.Combine(root, "runtime");
            string rtOld = Path.Combine(root, "runtime.old-" + id);
            if (Directory.Exists(rt))
            {
                try
                {
                    MoveDir(rt, rtOld);
                }
                catch (Exception e) when (e is IOException || e is UnauthorizedAccessException)
                {
                    throw new KdException("La carpeta de K-Snag está en uso. Cierra K-Snag y cualquier ventana abierta en esa carpeta, y vuelve a intentarlo.", e.Message);
                }
            }
            try
            {
                MoveDir(Path.Combine(stg, "runtime"), rt);
            }
            catch (Exception e) when (e is IOException || e is UnauthorizedAccessException)
            {
                if (Directory.Exists(rtOld)) { try { MoveDir(rtOld, rt); } catch (Exception) { } }
                throw new KdException("No se pudieron copiar los archivos de K-Snag.", e.Message);
            }

            // 2. Código de la app: app\<versión> (las versiones anteriores se quedan hasta el final).
            string apps = Path.Combine(root, "app");
            Directory.CreateDirectory(apps);
            string appDir = Path.Combine(apps, ver);
            string appOld = null;
            if (Directory.Exists(appDir))
            {
                appOld = appDir + ".old-" + id;
                MoveDir(appDir, appOld);
            }
            MoveDir(Path.Combine(stg, @"app\" + ver), appDir);

            // 3. El resto (launch.pyw, uninstall.cmd, icono, licencias...). "current" va al final.
            foreach (string item in Directory.GetFileSystemEntries(stg))
            {
                string name = Path.GetFileName(item);
                if (name == "runtime" || name == "app" || name == "current") continue;
                string dest = Path.Combine(root, name);
                if (Directory.Exists(item))
                {
                    RemoveDir(dest);
                    MoveDir(item, dest);
                }
                else
                {
                    File.Copy(item, dest, true);
                }
            }
            File.Copy(Path.Combine(stg, "current"), Path.Combine(root, "current"), true);

            RemoveDir(rtOld);
            if (appOld != null) RemoveDir(appOld);
            RemoveDir(stg);
            _staging = null;
        }

        private void StepFinish()
        {
            string py = Path.Combine(Root, @"runtime\python.exe");
            string cl = Quote(Path.Combine(Root, "launch.pyw")) + " --install-finish --version " + BuildInfo.Version;
            if (Desktop) cl += " --desktop";
            if (_opt.NoRegistry) cl += " --no-registry";
            if (_opt.ShortcutDir != null) cl += " --shortcut-dir " + Quote(_opt.ShortcutDir);
            var psi = new ProcessStartInfo(py, cl)
            {
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                StandardOutputEncoding = Encoding.UTF8,
                StandardErrorEncoding = Encoding.UTF8,
                WorkingDirectory = Root,
            };
            psi.EnvironmentVariables["PYTHONIOENCODING"] = "utf-8";
            psi.EnvironmentVariables["PYTHONUTF8"] = "1";
            Log.Write("> " + py + " " + cl);
            using (Process p = Process.Start(psi))
            {
                var err = p.StandardError.ReadToEndAsync();
                string stdout = p.StandardOutput.ReadToEnd();
                p.WaitForExit();
                string output = (stdout + Environment.NewLine + err.Result).Trim();
                Log.Write("install-finish (" + p.ExitCode + "): " + output);
                if (p.ExitCode != 0)
                {
                    throw new KdException("No se pudieron crear los accesos directos ni registrar K-Snag en Windows.", output);
                }
            }
        }

        private void StepCleanup()
        {
            if (_tempDir != null) { RemoveDir(_tempDir); _tempDir = null; }
            if (_staging != null) { RemoveDir(_staging); _staging = null; }
            Log.Write("Instalado K-Snag " + BuildInfo.Version + " en " + Root);
        }

        /// <summary>Mensaje y detalle para mostrar a partir de cualquier error.</summary>
        public static KdException Describe(Exception e)
        {
            for (Exception x = e; x != null; x = x.InnerException)
            {
                var kd = x as KdException;
                if (kd != null) return kd;
            }
            return new KdException("Algo salió mal durante la instalación.", e.Message);
        }
    }
}
