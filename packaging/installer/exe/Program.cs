using System;
using System.Threading;
using System.Windows.Forms;

namespace KSnagInstaller
{
    /// <summary>
    /// Instalar-K-Snag.exe: instala K-Snag para el usuario actual, sin administrador. Es el mismo instalador que
    /// Instalar-K-Snag.cmd (packaging/installer/Instalar-K-Snag.cmd.template), escrito en C# para poder firmarlo.
    /// Códigos de salida: 0 instalado, 1 error o cancelado.
    ///
    /// Es una app de ventana: desde una consola, Windows no espera a que termine. Para esperar el código de salida
    /// usa "start /wait" (cmd) o "Start-Process -Wait -PassThru" (PowerShell).
    /// </summary>
    internal static class Program
    {
        [STAThread]
        private static int Main(string[] args)
        {
            Options opt;
            try
            {
                opt = Options.Parse(args);
            }
            catch (KdException e)
            {
                string mode = "ui";
                foreach (string a in args)
                {
                    if (a.Equals("--quiet", StringComparison.OrdinalIgnoreCase)) mode = "quiet";
                    else if (a.Equals("--console", StringComparison.OrdinalIgnoreCase) && mode != "quiet") mode = "console";
                }
                Log.Write("ERROR de opciones: " + e.Message + " " + e.Detail);
                if (Native.ConnectConsole(mode == "console") != null)
                {
                    Console.Error.WriteLine("ERROR: " + e.Message + " " + e.Detail);
                    Console.WriteLine(Options.HelpText());
                }
                if (mode == "ui")
                {
                    Application.EnableVisualStyles();
                    MessageBox.Show(e.Message + " " + e.Detail, "Instalar K-Snag", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                }
                return 1;
            }

            if (opt.Help)
            {
                if (Native.ConnectConsole(false) != null)
                {
                    Console.WriteLine(Options.HelpText());
                }
                else
                {
                    Application.EnableVisualStyles();
                    MessageBox.Show(Options.HelpText(), "Instalar K-Snag", MessageBoxButtons.OK, MessageBoxIcon.Information);
                }
                return 0;
            }

            if (opt.Mode == "ui")
            {
                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);
                var engine = new Engine(opt);
                using (var form = new InstallerForm(engine, opt))
                {
                    Application.Run(form);
                    return form.ExitCode;
                }
            }
            return RunConsole(opt);
        }

        private static void Say(string text)
        {
            try { Console.WriteLine(text); } catch (Exception) { }
        }

        /// <summary>Modo consola (--console) y silencioso (--quiet).</summary>
        private static int RunConsole(Options opt)
        {
            bool quiet = opt.Mode == "quiet";
            string console = Native.ConnectConsole(!quiet);
            var engine = new Engine(opt);
            if (opt.Desktop.HasValue) engine.Desktop = opt.Desktop.Value;
            else if (quiet) engine.Desktop = false;
            else engine.Desktop = engine.DefaultDesktop;

            engine.Confirm = message =>
            {
                if (quiet || console == null) return true;
                Console.Write(message + " (S/N) ");
                string answer = Console.ReadLine() ?? "";
                answer = answer.Trim();
                return answer.StartsWith("s", StringComparison.OrdinalIgnoreCase) || answer.StartsWith("y", StringComparison.OrdinalIgnoreCase);
            };

            Say("");
            Say("Instalador de K-Snag " + BuildInfo.Version);
            Say("Carpeta: " + engine.Root);
            int rc = 0;
            Exception failure = null;
            var worker = new Thread(() =>
            {
                try
                {
                    engine.Run(i => Say(string.Format("[{0}/{1}] {2}", i + 1, engine.Steps.Count, engine.Steps[i].Text)));
                }
                catch (Exception e)
                {
                    failure = e;
                }
            });
            worker.IsBackground = true;
            worker.Start();
            int lastPct = -1;
            int lastStep = -1;
            while (!worker.Join(200))
            {
                int step = engine.StepIndex;
                if (step != lastStep) { lastStep = step; lastPct = -1; }
                double f = engine.StepFraction;
                if (!quiet && f >= 0)
                {
                    // Progreso del paso (descarga, extracción), de 10 en 10.
                    int pct = (int)(Math.Floor(f * 100 / 10) * 10);
                    if (pct != lastPct && pct > 0) { Say("      " + pct + " %"); lastPct = pct; }
                }
            }
            if (failure != null)
            {
                KdException fail = Engine.Describe(failure);
                Log.Write("ERROR: " + fail.Message + " | " + fail.Detail + " | " + failure);
                engine.CleanTemp();
                Say("");
                Say("ERROR: " + fail.Message);
                if (fail.Detail.Length > 0) Say("  " + fail.Detail);
                Say("Registro: " + Log.File);
                rc = 1;
            }
            else
            {
                Say("");
                Say("¡Listo! K-Snag " + BuildInfo.Version + " está instalado en " + engine.Root);
                bool launch = opt.Launch ?? !quiet;
                if (launch)
                {
                    try { engine.StartApp(); } catch (Exception e) { Log.Write("No se pudo abrir la app: " + e.Message); }
                }
            }
            if (console == "allocated")
            {
                Say("");
                Say("Presiona Enter para cerrar.");
                try { Console.ReadLine(); } catch (Exception) { }
            }
            return rc;
        }
    }
}
