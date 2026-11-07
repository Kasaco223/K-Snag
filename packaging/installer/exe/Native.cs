using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;

namespace KSnagInstaller
{
    /// <summary>Llamadas a Windows que .NET Framework no expone.</summary>
    internal static class Native
    {
        private const uint PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;
        private const int ATTACH_PARENT_PROCESS = -1;
        private const int STD_OUTPUT_HANDLE = -11;
        private const int WTS_USER_NAME = 5;
        private const int WTS_DOMAIN_NAME = 7;

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern IntPtr OpenProcess(uint access, bool inherit, int pid);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool CloseHandle(IntPtr handle);

        [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
        private static extern bool QueryFullProcessImageName(IntPtr process, int flags, StringBuilder name, ref int size);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool AttachConsole(int pid);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool AllocConsole();

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern IntPtr GetStdHandle(int which);

        [DllImport("kernel32.dll")]
        private static extern uint GetFileType(IntPtr handle);

        [DllImport("wtsapi32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
        private static extern bool WTSQuerySessionInformation(IntPtr server, int sessionId, int infoClass, out IntPtr buffer, out int bytes);

        [DllImport("wtsapi32.dll")]
        private static extern void WTSFreeMemory(IntPtr memory);

        /// <summary>Ruta completa del ejecutable de un proceso (también si es de 64 bits o de otro usuario).</summary>
        public static string ProcessPath(int pid)
        {
            IntPtr h = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid);
            if (h == IntPtr.Zero) return null;
            try
            {
                var sb = new StringBuilder(32768);
                int size = sb.Capacity;
                return QueryFullProcessImageName(h, 0, sb, ref size) ? sb.ToString(0, size) : null;
            }
            finally
            {
                CloseHandle(h);
            }
        }

        /// <summary>
        /// Conecta Console.Out a la consola de quien abrió el instalador (o a la salida redirigida). Con
        /// <paramref name="allocate"/>, si no hay ninguna, abre una ventana de consola nueva. Devuelve
        /// "inherited", "attached", "allocated" o null.
        /// </summary>
        public static string ConnectConsole(bool allocate)
        {
            IntPtr std = GetStdHandle(STD_OUTPUT_HANDLE);
            // 0 = FILE_TYPE_UNKNOWN: sin salida heredada (lo normal en una app de ventana).
            if (std != IntPtr.Zero && std != new IntPtr(-1) && GetFileType(std) != 0)
            {
                ResetConsoleWriters();
                return "inherited";
            }
            if (AttachConsole(ATTACH_PARENT_PROCESS))
            {
                ResetConsoleWriters();
                return "attached";
            }
            if (allocate && AllocConsole())
            {
                ResetConsoleWriters();
                return "allocated";
            }
            return null;
        }

        private static void ResetConsoleWriters()
        {
            try { Console.OutputEncoding = Encoding.UTF8; } catch (IOException) { } catch (ArgumentException) { }
            var stdout = new StreamWriter(Console.OpenStandardOutput(), new UTF8Encoding(false)) { AutoFlush = true };
            var stderr = new StreamWriter(Console.OpenStandardError(), new UTF8Encoding(false)) { AutoFlush = true };
            Console.SetOut(stdout);
            Console.SetError(stderr);
        }

        /// <summary>"DOMINIO\usuario" de quien inició la sesión de Windows en la que corre el instalador.</summary>
        public static string SessionUser(int sessionId)
        {
            string user = QuerySession(sessionId, WTS_USER_NAME);
            if (string.IsNullOrEmpty(user)) return null;
            string domain = QuerySession(sessionId, WTS_DOMAIN_NAME);
            return string.IsNullOrEmpty(domain) ? user : domain + "\\" + user;
        }

        private static string QuerySession(int sessionId, int infoClass)
        {
            IntPtr buffer;
            int bytes;
            if (!WTSQuerySessionInformation(IntPtr.Zero, sessionId, infoClass, out buffer, out bytes)) return null;
            try
            {
                return Marshal.PtrToStringUni(buffer);
            }
            finally
            {
                WTSFreeMemory(buffer);
            }
        }
    }
}
