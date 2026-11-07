using System;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Text;
using System.Threading;
using System.Windows.Forms;

namespace KSnagInstaller
{
    /// <summary>La ventana del instalador: mismo diseño que la del .cmd (encabezado oscuro, logo, degradado).</summary>
    internal sealed class InstallerForm : Form
    {
        private static readonly Color C1 = Color.FromArgb(0x63, 0x66, 0xF1);
        private static readonly Color C2 = Color.FromArgb(0x8B, 0x5C, 0xF6);
        private static readonly Color C3 = Color.FromArgb(0xF9, 0x73, 0x62);
        private static readonly Color Dark = Color.FromArgb(0x10, 0x12, 0x18);
        private static readonly Color TextColor = Color.FromArgb(0x1F, 0x23, 0x30);
        private static readonly Color Muted = Color.FromArgb(0x6B, 0x70, 0x85);
        private static readonly Color Red = Color.FromArgb(0xC0, 0x36, 0x3A);
        private static readonly Color Track = Color.FromArgb(0xE6, 0xE7, 0xF0);

        private readonly Options _opt;
        private Engine _engine;
        private readonly float _k;
        private readonly Font _font = new Font("Segoe UI", 10f);
        private readonly Font _fontSmall = new Font("Segoe UI", 9f);
        private readonly Font _fontBold = new Font("Segoe UI Semibold", 10f);
        private readonly Font _fontTitle = new Font("Segoe UI Semibold", 18f);
        private readonly Font _fontSub = new Font("Segoe UI", 9.5f);

        private readonly PictureBox _header;
        private readonly Label _intro;
        private readonly CheckBox _desktop;
        private readonly PictureBox _bar;
        private readonly Label _status;
        private readonly Label _detail;
        private readonly LinkLabel _logLink;
        private readonly Button _primary;
        private readonly Button _secondary;
        private readonly System.Windows.Forms.Timer _timer;
        private readonly string _startText;
        private readonly string _subtitle;

        private string _state = "ready"; // ready, running, done, error
        private Thread _worker;
        private int _shownStep = -1;
        private bool _closeWhenStopped;
        private int _tick;
        private double _progress;

        public InstallerForm(Engine engine, Options opt)
        {
            _engine = engine;
            _opt = opt;
            ExitCode = 1;
            using (Graphics g = CreateGraphics()) _k = g.DpiX / 96f;

            SuspendLayout();
            AutoScaleMode = AutoScaleMode.None;
            Text = "Instalar K-Snag";
            ClientSize = new Size(S(540), S(400));
            FormBorderStyle = FormBorderStyle.FixedSingle;
            MaximizeBox = false;
            StartPosition = FormStartPosition.CenterScreen;
            BackColor = Color.White;
            Font = _font;
            try { Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch (Exception) { }

            // Encabezado oscuro con el logo y una línea con el degradado de la marca.
            _subtitle = "Instalador · versión " + BuildInfo.Version;
            _header = new PictureBox { Dock = DockStyle.Top, Height = S(108), BackColor = Dark };
            _header.Paint += PaintHeader;
            Controls.Add(_header);

            _intro = new Label { ForeColor = TextColor };
            _intro.SetBounds(S(26), S(126), S(488), S(46));
            if (engine.Existing == null)
            {
                _intro.Text = "Descarga tus videos favoritos en la mejor calidad. K-Snag se instala solo para tu usuario y no necesita permisos de administrador.";
                _startText = "Instalar";
            }
            else if (engine.Existing == BuildInfo.Version)
            {
                _intro.Text = "K-Snag " + BuildInfo.Version + " ya está instalado. Puedes reinstalarlo para reparar la instalación; tus ajustes y tu historial se conservan.";
                _startText = "Reinstalar";
            }
            else
            {
                _intro.Text = "Se actualizará K-Snag " + engine.Existing + " a la versión " + BuildInfo.Version + ". Tus ajustes y tu historial se conservan.";
                _startText = "Actualizar";
            }
            Controls.Add(_intro);

            var pathLabel = new Label { Font = _fontSmall, ForeColor = Muted, AutoEllipsis = true, Text = "Carpeta: " + engine.Root };
            pathLabel.SetBounds(S(26), S(176), S(488), S(20));
            Controls.Add(pathLabel);

            _desktop = new CheckBox { Text = "Crear acceso directo en el escritorio", ForeColor = TextColor };
            _desktop.SetBounds(S(26), S(204), S(488), S(26));
            _desktop.Checked = opt.Desktop ?? engine.DefaultDesktop;
            Controls.Add(_desktop);

            // Barra de progreso propia (redondeada, con el degradado de la marca).
            _bar = new PictureBox { BackColor = Color.White, Visible = false };
            _bar.SetBounds(S(26), S(246), S(488), S(10));
            _bar.Paint += PaintBar;
            Controls.Add(_bar);

            _status = new Label { ForeColor = TextColor, Font = _fontBold };
            _status.SetBounds(S(26), S(266), S(488), S(24));
            Controls.Add(_status);

            _detail = new Label { Font = _fontSmall, ForeColor = Muted };
            _detail.SetBounds(S(26), S(290), S(488), S(44));
            Controls.Add(_detail);

            _logLink = new LinkLabel { Font = _fontSmall, Text = "Ver el registro de instalación", LinkColor = C1, Visible = false };
            _logLink.SetBounds(S(26), S(357), S(200), S(22));
            _logLink.LinkClicked += (s, e) =>
            {
                try { Process.Start(new ProcessStartInfo("notepad.exe", Engine.Quote(Log.File)) { UseShellExecute = true }); } catch (Exception) { }
            };
            Controls.Add(_logLink);

            _secondary = NewButton("Cancelar", 236, 350, 116, false);
            _primary = NewButton("Instalar", 362, 350, 152, true);
            Controls.Add(_secondary);
            Controls.Add(_primary);
            AcceptButton = _primary;

            _timer = new System.Windows.Forms.Timer { Interval = 50 };
            _timer.Tick += (s, e) => OnTick();

            _primary.Click += (s, e) =>
            {
                if (_state == "done")
                {
                    try { _engine.StartApp(); } catch (Exception ex) { Log.Write("No se pudo abrir la app: " + ex.Message); }
                    Close();
                }
                else
                {
                    StartInstall();
                }
            };
            _secondary.Click += (s, e) => Close();
            FormClosing += OnFormClosing;
            Shown += (s, e) =>
            {
                TopMost = true;
                Activate();
                TopMost = false;
                _primary.Focus();
            };

            SetState("ready");
            SetStatus("", "", false);
            ResumeLayout(false);
        }

        public int ExitCode { get; private set; }

        private int S(int value)
        {
            return (int)Math.Round(value * _k);
        }

        // --- Dibujo ------------------------------------------------------------------------------

        private static GraphicsPath RoundPath(float x, float y, float w, float h, float r)
        {
            var p = new GraphicsPath();
            float d = Math.Min(r * 2, Math.Min(w, h));
            if (d <= 1)
            {
                p.AddRectangle(new RectangleF(x, y, w, h));
                return p;
            }
            p.AddArc(x, y, d, d, 180, 90);
            p.AddArc(x + w - d, y, d, d, 270, 90);
            p.AddArc(x + w - d, y + h - d, d, d, 0, 90);
            p.AddArc(x, y + h - d, d, d, 90, 90);
            p.CloseFigure();
            return p;
        }

        private static LinearGradientBrush Gradient(float x1, float y1, float x2, float y2)
        {
            if (Math.Abs(x2 - x1) < 0.5f && Math.Abs(y2 - y1) < 0.5f) x2 = x1 + 1;
            var b = new LinearGradientBrush(new PointF(x1, y1), new PointF(x2, y2), C1, C3);
            b.InterpolationColors = new ColorBlend(3) { Colors = new[] { C1, C2, C3 }, Positions = new[] { 0f, 0.55f, 1f } };
            b.WrapMode = WrapMode.TileFlipXY;
            return b;
        }

        private static PointF[] Points(int[] coords, float x, float y, float k)
        {
            var pts = new PointF[coords.Length / 2];
            for (int i = 0; i < pts.Length; i++) pts[i] = new PointF(x + coords[2 * i] * k, y + coords[2 * i + 1] * k);
            return pts;
        }

        /// <summary>Misma geometría que packaging/assets/logo.svg (viewBox 256).</summary>
        private static void DrawLogo(Graphics g, float x, float y, float size)
        {
            float k = size / 256f;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            float rx = x + 8 * k, ry = y + 8 * k, rs = 240 * k;
            using (GraphicsPath path = RoundPath(rx, ry, rs, rs, 56 * k))
            using (LinearGradientBrush brush = Gradient(rx, ry, rx + rs, ry + rs))
            {
                g.FillPath(brush, path);
            }
            g.FillPolygon(Brushes.White, Points(new[] { 68, 52, 102, 52, 102, 112, 154, 52, 196, 52, 136, 118, 170, 157, 126, 157, 102, 129, 102, 192, 68, 192 }, x, y, k));
            g.FillEllipse(Brushes.White, x + 132 * k, y + 132 * k, 92 * k, 92 * k);
            using (var indigo = new SolidBrush(C1))
            {
                g.FillPolygon(indigo, Points(new[] { 168, 150, 188, 150, 188, 178, 204, 178, 178, 206, 152, 178, 168, 178 }, x, y, k));
            }
        }

        private void PaintHeader(object sender, PaintEventArgs e)
        {
            Graphics g = e.Graphics;
            g.TextRenderingHint = TextRenderingHint.ClearTypeGridFit;
            DrawLogo(g, S(26), S(22), S(64));
            g.DrawString("K-Snag", _fontTitle, Brushes.White, S(104), S(24));
            using (var sb = new SolidBrush(Color.FromArgb(0xA9, 0xAC, 0xC4)))
            {
                g.DrawString(_subtitle, _fontSub, sb, S(107), S(60));
            }
            int w = _header.ClientSize.Width;
            using (LinearGradientBrush lb = Gradient(0, 0, w, 0))
            {
                g.FillRectangle(lb, 0, _header.ClientSize.Height - S(4), w, S(4));
            }
        }

        private void PaintBar(object sender, PaintEventArgs e)
        {
            Graphics g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            float w = _bar.ClientSize.Width - 1;
            float h = _bar.ClientSize.Height - 1;
            using (GraphicsPath track = RoundPath(0, 0, w, h, h / 2))
            {
                using (var tb = new SolidBrush(Track)) g.FillPath(tb, track);
                float fw = (float)(Math.Max(0.0, Math.Min(1.0, _progress)) * w);
                if (fw >= h)
                {
                    using (GraphicsPath fill = RoundPath(0, 0, fw, h, h / 2))
                    using (LinearGradientBrush gb = Gradient(0, 0, w + 1, 0))
                    {
                        g.FillPath(gb, fill);
                    }
                }
                if (_state == "running" && _engine.Busy)
                {
                    // Brillo que avanza mientras un paso no tiene progreso medible.
                    float span = S(90);
                    float x = (_tick * S(7)) % (w + span) - span;
                    g.SetClip(track);
                    using (var shine = new LinearGradientBrush(new PointF(x, 0), new PointF(x + span, 0),
                        Color.FromArgb(0, 99, 102, 241), Color.FromArgb(150, 139, 92, 246)))
                    {
                        shine.WrapMode = WrapMode.TileFlipX;
                        g.FillRectangle(shine, x, 0, span, h + 1);
                    }
                    g.ResetClip();
                }
            }
        }

        private Button NewButton(string text, int x, int y, int w, bool primary)
        {
            var b = new Button { Text = text, FlatStyle = FlatStyle.Flat, UseVisualStyleBackColor = false, Cursor = Cursors.Hand };
            b.SetBounds(S(x), S(y), S(w), S(36));
            b.FlatAppearance.BorderSize = 0;
            if (primary)
            {
                b.BackColor = C1;
                b.ForeColor = Color.White;
                b.FlatAppearance.MouseOverBackColor = Color.FromArgb(0x55, 0x58, 0xE6);
                b.FlatAppearance.MouseDownBackColor = Color.FromArgb(0x48, 0x4B, 0xD6);
                b.Font = _fontBold;
            }
            else
            {
                b.BackColor = Color.FromArgb(0xEE, 0xEF, 0xF5);
                b.ForeColor = TextColor;
                b.FlatAppearance.MouseOverBackColor = Color.FromArgb(0xE2, 0xE4, 0xEE);
                b.FlatAppearance.MouseDownBackColor = Color.FromArgb(0xD6, 0xD9, 0xE6);
            }
            return b;
        }

        // --- Estado ------------------------------------------------------------------------------

        private void SetStatus(string text, string detail, bool isError)
        {
            _status.Text = text;
            _status.ForeColor = isError ? Red : TextColor;
            if (detail != null) _detail.Text = detail;
            // El estado puede ocupar varias líneas (errores): el detalle se acomoda debajo.
            Size measured = TextRenderer.MeasureText(text, _status.Font, new Size(_status.Width, 0), TextFormatFlags.WordBreak);
            int h = Math.Max(S(22), Math.Min(S(64), measured.Height + 2));
            _status.Height = h;
            _detail.Top = _status.Top + h + 2;
            _detail.Height = Math.Max(S(18), S(346) - _detail.Top);
        }

        private void SetState(string state)
        {
            _state = state;
            switch (state)
            {
                case "ready":
                    _primary.Text = _startText; _primary.Enabled = true;
                    _secondary.Text = "Cancelar"; _secondary.Enabled = true;
                    _desktop.Enabled = true; _bar.Visible = false; _logLink.Visible = false;
                    break;
                case "running":
                    _primary.Text = "Instalando…"; _primary.Enabled = false;
                    _secondary.Text = "Cancelar"; _secondary.Enabled = !_engine.InCritical && !_closeWhenStopped;
                    _desktop.Enabled = false; _bar.Visible = true; _logLink.Visible = false;
                    break;
                case "done":
                    _primary.Text = "Abrir K-Snag"; _primary.Enabled = true;
                    _secondary.Text = "Cerrar"; _secondary.Enabled = true;
                    _desktop.Enabled = false; _logLink.Visible = false;
                    _primary.Focus();
                    break;
                case "error":
                    _primary.Text = "Reintentar"; _primary.Enabled = true;
                    _secondary.Text = "Cerrar"; _secondary.Enabled = true;
                    _desktop.Enabled = true; _logLink.Visible = true;
                    break;
            }
        }

        private void StartInstall()
        {
            if (_state == "error") _engine = new Engine(_opt); // reintento desde cero
            Engine engine = _engine;
            engine.Desktop = _desktop.Checked;
            engine.Confirm = message => (bool)Invoke(new Func<bool>(() =>
                MessageBox.Show(this, message, "Instalar K-Snag", MessageBoxButtons.YesNo, MessageBoxIcon.Question) == DialogResult.Yes));
            _progress = 0;
            _shownStep = -1;
            SetStatus(engine.Steps[0].Text, "", false);
            SetState("running");
            _worker = new Thread(() =>
            {
                Exception failure = null;
                try
                {
                    engine.Run(null);
                }
                catch (Exception e)
                {
                    failure = e;
                }
                try
                {
                    BeginInvoke(new Action(() => OnFinished(engine, failure)));
                }
                catch (InvalidOperationException) { }
            });
            _worker.IsBackground = true;
            _worker.Start();
            _timer.Start();
        }

        private void OnTick()
        {
            _tick++;
            if (_state != "running") return;
            int step = _engine.StepIndex;
            if (step != _shownStep && step < _engine.Steps.Count)
            {
                _shownStep = step;
                if (!_closeWhenStopped) SetStatus(_engine.Steps[step].Text, "", false);
                SetState("running");
            }
            string detail = _engine.Detail;
            if (!_closeWhenStopped && detail.Length > 0 && _detail.Text != detail) _detail.Text = detail;
            _progress = _engine.Progress;
            _bar.Invalidate();
        }

        private void OnFinished(Engine engine, Exception failure)
        {
            _timer.Stop();
            _worker = null;
            if (failure == null)
            {
                _progress = 1.0;
                ExitCode = 0;
                string where = "Lo encontrarás en el menú Inicio";
                if (engine.Desktop && !_opt.NoRegistry) where += " y en el escritorio";
                SetStatus("¡Listo! K-Snag está instalado.", where + ".", false);
                SetState("done");
                _bar.Invalidate();
                if (_closeWhenStopped) Close();
                return;
            }
            ExitCode = 1;
            if (failure is InstallCanceledException)
            {
                Log.Write("Instalación cancelada por el usuario");
            }
            KdException fail = Engine.Describe(failure);
            if (!(failure is InstallCanceledException)) Log.Write("ERROR: " + fail.Message + " | " + fail.Detail + " | " + failure);
            engine.CleanTemp();
            if (_closeWhenStopped)
            {
                Close();
                return;
            }
            string detail = fail.Detail;
            if (detail.Length > 240) detail = detail.Substring(0, 240) + "…";
            SetStatus(fail.Message, detail, true);
            SetState("error");
            _bar.Invalidate();
        }

        private void OnFormClosing(object sender, FormClosingEventArgs e)
        {
            if (_state != "running" || _worker == null) return;
            if (_closeWhenStopped)
            {
                e.Cancel = true;
                return;
            }
            if (_engine.InCritical)
            {
                MessageBox.Show(this, "Espera un momento: se están copiando los archivos de K-Snag.", "Instalar K-Snag");
                e.Cancel = true;
                return;
            }
            DialogResult answer = MessageBox.Show(this, "¿Cancelar la instalación?", "Instalar K-Snag", MessageBoxButtons.YesNo, MessageBoxIcon.Question);
            e.Cancel = true;
            if (answer != DialogResult.Yes || _engine.InCritical || _worker == null) return;
            // Se detiene en el próximo punto seguro; al terminar, OnFinished limpia y cierra la ventana.
            _closeWhenStopped = true;
            _engine.RequestCancel();
            SetStatus("Cancelando…", "", false);
            SetState("running");
        }

        protected override void Dispose(bool disposing)
        {
            if (disposing)
            {
                _timer.Dispose();
                _font.Dispose();
                _fontSmall.Dispose();
                _fontBold.Dispose();
                _fontTitle.Dispose();
                _fontSub.Dispose();
            }
            base.Dispose(disposing);
        }
    }
}
