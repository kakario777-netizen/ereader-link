using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace EreaderLink
{
    public class MainForm : Form
    {
        // ── 연결 상태 ──
        Api api;
        DeviceInfo dev;
        string cur;                       // 지금 보고 있는 이북리더 폴더
        List<Item> curItems = new List<Item>();
        readonly Stack<string> back = new Stack<string>(), fwd = new Stack<string>();
        readonly Finder finder = new Finder();
        bool connecting;
        int beatFails;
        bool beatBusy;

        // ── 전송 진행 ──
        readonly SemaphoreSlim gate = new SemaphoreSlim(1, 1);
        long pTotal, pDone;
        string pText;
        DateTime pStart;
        CancellationTokenSource pCts;
        volatile bool transferring;

        // ── 화면 ──
        readonly float k;
        ToolStrip bar;
        ToolStripButton bBack, bFwd, bUp, bRefresh, bNew, bUpload, bExport, bDelete;
        ToolStripTextBox addr;
        SplitContainer split;
        TreeView tree;
        ListView list;
        ImageList icons;
        readonly Dictionary<string, string> typeNames = new Dictionary<string, string>();
        StatusStrip status;
        ToolStripStatusLabel lConn, lCount, lProg, lCancel, lUpdate;
        Updater.Release newVersion;
        bool restarting;
        ToolStripProgressBar prog;
        Panel wait;
        Label waitTitle, waitText;
        TextBox ipBox;
        ContextMenuStrip menu;
        System.Windows.Forms.Timer uiTimer, beat;
        int sortCol;
        bool sortDesc;
        RemoteDataObject dragObj;
        bool treeSync;

        static readonly CultureInfo Ko = new CultureInfo("ko-KR");
        static readonly string TempRoot = Path.Combine(Path.GetTempPath(), "EreaderLink");
        static readonly string SettingsDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "EreaderLink");

        public MainForm()
        {
            using (var g = CreateGraphics()) k = g.DpiX / 96f;
            Text = "이북 링크  (빌드 #" + Updater.Current + ")";
            Font = new Font("Malgun Gothic", 9f);
            Size = new Size(S(1000), S(660));
            StartPosition = FormStartPosition.CenterScreen;
            KeyPreview = true;
            try { Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }

            BuildUi();
            try { if (Directory.Exists(TempRoot)) Directory.Delete(TempRoot, true); } catch { }

            finder.Found += (host, port, name) =>
            {
                if (IsHandleCreated) BeginInvoke(new Action(() => _ = Connect(host, port)));
            };
        }

        int S(int px) => (int)(px * k);

        // ════════════════════════ 화면 만들기 ════════════════════════

        void BuildUi()
        {
            SuspendLayout();

            bar = new ToolStrip { GripStyle = ToolStripGripStyle.Hidden, Padding = new Padding(S(4), S(2), S(4), S(2)), Stretch = true };
            bBack = Btn("←", "뒤로 (Alt+←)", () => _ = GoBack());
            bFwd = Btn("→", "앞으로 (Alt+→)", () => _ = GoForward());
            bUp = Btn("↑", "상위 폴더 (Backspace)", () => _ = GoUp());
            addr = new ToolStripTextBox { AutoSize = false, Width = S(400) };
            addr.KeyDown += (s, e) =>
            {
                if (e.KeyCode == Keys.Enter) { e.SuppressKeyPress = true; var p = ParseAddr(addr.Text); if (p != null) _ = Go(p); }
            };
            bRefresh = Btn("새로고침", "새로고침 (F5)", () => _ = Reload());
            bNew = Btn("새 폴더", "새 폴더 만들기", () => _ = NewFolder());
            bUpload = Btn("PC에서 올리기", "PC 파일을 지금 폴더로 보내기", () => _ = UploadPick());
            bExport = Btn("PC로 내보내기", "선택한 항목을 PC 폴더에 저장", () => _ = ExportPick());
            bDelete = Btn("삭제", "선택한 항목 지우기 (Delete)", () => _ = DeleteSelected());
            bar.Items.AddRange(new ToolStripItem[] { bBack, bFwd, bUp, new ToolStripSeparator(), addr, new ToolStripSeparator(),
                bRefresh, bNew, bUpload, bExport, bDelete });
            bar.Resize += (s, e) => FitAddr();

            icons = new ImageList { ColorDepth = ColorDepth.Depth32Bit, ImageSize = SystemInformation.SmallIconSize };

            tree = new TreeView
            {
                Dock = DockStyle.Fill,
                HideSelection = false,
                ShowLines = false,
                FullRowSelect = true,
                ImageList = icons,
                AllowDrop = true,
                ItemHeight = S(24),
                BorderStyle = BorderStyle.None
            };
            tree.BeforeExpand += Tree_BeforeExpand;
            tree.AfterSelect += (s, e) =>
            {
                if (treeSync || e.Action == TreeViewAction.Unknown) return;
                if (e.Node?.Tag is string p) _ = Go(p);
            };
            tree.DragEnter += Target_DragOver;
            tree.DragOver += Target_DragOver;
            tree.DragDrop += Target_DragDrop;

            list = new ListView
            {
                Dock = DockStyle.Fill,
                View = View.Details,
                FullRowSelect = true,
                HideSelection = false,
                LabelEdit = true,
                AllowDrop = true,
                SmallImageList = icons,
                BorderStyle = BorderStyle.None
            };
            list.Columns.Add("이름", S(380));
            list.Columns.Add("수정한 날짜", S(160));
            list.Columns.Add("유형", S(140));
            var cSize = list.Columns.Add("크기", S(100));
            cSize.TextAlign = HorizontalAlignment.Right;
            list.ColumnClick += (s, e) =>
            {
                if (sortCol == e.Column) sortDesc = !sortDesc; else { sortCol = e.Column; sortDesc = false; }
                SortList();
            };
            list.DoubleClick += (s, e) => _ = OpenSelected();
            list.KeyDown += List_KeyDown;
            list.AfterLabelEdit += List_AfterLabelEdit;
            list.ItemDrag += List_ItemDrag;
            list.QueryContinueDrag += (s, e) =>
            {
                if (e.Action == DragAction.Drop && dragObj != null) dragObj.Dropped = true;
            };
            list.DragEnter += Target_DragOver;
            list.DragOver += Target_DragOver;
            list.DragDrop += Target_DragDrop;
            list.SelectedIndexChanged += (s, e) => UpdateButtons();

            menu = new ContextMenuStrip();
            menu.Opening += (s, e) => BuildMenu();
            list.ContextMenuStrip = menu;

            split = new SplitContainer { Dock = DockStyle.Fill, FixedPanel = FixedPanel.Panel1, SplitterWidth = S(5) };
            split.Panel1.Controls.Add(tree);
            split.Panel2.Controls.Add(list);

            status = new StatusStrip { SizingGrip = true };
            lConn = new ToolStripStatusLabel("연결 안 됨");
            lCount = new ToolStripStatusLabel("") { BorderSides = ToolStripStatusLabelBorderSides.Left };
            lProg = new ToolStripStatusLabel("") { Spring = true, TextAlign = ContentAlignment.MiddleLeft, BorderSides = ToolStripStatusLabelBorderSides.Left };
            prog = new ToolStripProgressBar { Maximum = 1000, Width = S(160), Visible = false };
            lCancel = new ToolStripStatusLabel("취소") { IsLink = true, Visible = false };
            lCancel.Click += (s, e) => { try { pCts?.Cancel(); } catch { } };
            lUpdate = new ToolStripStatusLabel("") { IsLink = true, Visible = false, BorderSides = ToolStripStatusLabelBorderSides.Left };
            lUpdate.Click += (s, e) => _ = DoUpdate();
            status.Items.AddRange(new ToolStripItem[] { lConn, lCount, lProg, prog, lCancel, lUpdate });

            // 연결 전 안내 화면
            wait = new Panel { Dock = DockStyle.Fill, BackColor = Color.White, Padding = new Padding(S(40)) };
            waitTitle = new Label
            {
                Text = "이북리더를 찾는 중…",
                Font = new Font("Malgun Gothic", 18f, FontStyle.Bold),
                AutoSize = true,
                Location = new Point(S(40), S(40))
            };
            waitText = new Label
            {
                Text = "1. 이북리더에서 '이북 링크' 앱을 켜 주세요.\n" +
                       "2. PC와 이북리더가 같은 와이파이(공유기)에 연결돼 있으면 자동으로 연결돼요.\n\n" +
                       "처음 실행할 때 윈도우 방화벽 창이 뜨면 '액세스 허용'을 눌러 주세요.\n" +
                       "그래도 안 되면 이북리더 앱 화면에 나온 IP를 아래에 넣고 '연결'을 눌러 주세요.",
                AutoSize = true,
                Location = new Point(S(42), S(90)),
                Font = new Font("Malgun Gothic", 10.5f)
            };
            ipBox = new TextBox { Location = new Point(S(42), S(230)), Width = S(180), Font = new Font("Malgun Gothic", 11f) };
            var ipBtn = new Button { Text = "연결", Location = new Point(S(230), S(229)), Size = new Size(S(80), S(30)) };
            ipBtn.Click += (s, e) => ConnectManual();
            ipBox.KeyDown += (s, e) => { if (e.KeyCode == Keys.Enter) { e.SuppressKeyPress = true; ConnectManual(); } };
            wait.Controls.AddRange(new Control[] { waitTitle, waitText, ipBox, ipBtn });

            Controls.Add(split);
            Controls.Add(wait);
            Controls.Add(bar);
            Controls.Add(status);
            wait.BringToFront();

            uiTimer = new System.Windows.Forms.Timer { Interval = 250 };
            uiTimer.Tick += (s, e) => TickProgress();
            uiTimer.Start();
            beat = new System.Windows.Forms.Timer { Interval = 3000 };
            beat.Tick += (s, e) => _ = Heartbeat();
            beat.Start();

            ResumeLayout(true);
            UpdateButtons();
        }

        ToolStripButton Btn(string text, string tip, Action click)
        {
            var b = new ToolStripButton(text) { ToolTipText = tip, DisplayStyle = ToolStripItemDisplayStyle.Text, Padding = new Padding(S(4), 0, S(4), 0) };
            b.Click += (s, e) => click();
            return b;
        }

        void FitAddr()
        {
            int w = bar.DisplayRectangle.Width - S(6);
            foreach (ToolStripItem it in bar.Items)
                if (it != addr) w -= it.Width + it.Margin.Horizontal;
            addr.Width = Math.Max(S(120), w - addr.Margin.Horizontal);
        }

        protected override void OnShown(EventArgs e)
        {
            base.OnShown(e);
            FitAddr();
            try { split.SplitterDistance = S(240); } catch { }
            ipBox.Text = LoadLast() ?? "";
            finder.Start();
            _ = TryLast();
            _ = CheckUpdate();
        }

        // ════════════════════════ 자체 업데이트 ════════════════════════

        async Task CheckUpdate()
        {
            await Task.Run(() => Updater.Cleanup());
            var r = await Task.Run(() => Updater.Check());
            if (r == null) return;
            newVersion = r;
            lUpdate.Text = "새 버전 #" + r.Build + " 업데이트";
            lUpdate.ToolTipText = "누르면 받아서 바로 다시 켜져요 (지금 #" + Updater.Current + ")";
            lUpdate.Visible = true;
            // 켤 때 새 버전이 있으면 바로 받아서 다시 켬 (전송 중이 아닐 때)
            if (!transferring) await DoUpdate();
        }

        async Task DoUpdate()
        {
            var r = newVersion;
            if (r == null) return;
            if (transferring)
            {
                MessageBox.Show(this, "전송이 끝난 뒤에 업데이트해 주세요.", "이북 링크");
                return;
            }
            lUpdate.Visible = false;
            bool ok = await Job("업데이트 받는 중", async ct =>
            {
                pTotal = r.Size;
                await Task.Run(() => Updater.Apply(r, n => Interlocked.Add(ref pDone, n), ct));
            });
            if (ok)
            {
                restarting = true;
                Close();
            }
            else lUpdate.Visible = true;
        }

        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            if (!restarting && transferring && MessageBox.Show(this, "전송 중이에요. 그래도 닫을까요?", "이북 링크",
                    MessageBoxButtons.YesNo, MessageBoxIcon.Warning) != DialogResult.Yes)
            {
                e.Cancel = true;
                return;
            }
            try { pCts?.Cancel(); } catch { }
            finder.Dispose();
            base.OnFormClosing(e);
        }

        protected override bool ProcessCmdKey(ref Message msg, Keys keyData)
        {
            if (keyData == (Keys.Alt | Keys.Left)) { _ = GoBack(); return true; }
            if (keyData == (Keys.Alt | Keys.Right)) { _ = GoForward(); return true; }
            if (keyData == (Keys.Alt | Keys.Up)) { _ = GoUp(); return true; }
            if (keyData == Keys.F5) { _ = Reload(); return true; }
            return base.ProcessCmdKey(ref msg, keyData);
        }

        // ════════════════════════ 연결 ════════════════════════

        async Task Connect(string host, int port)
        {
            if (api != null || connecting) return;
            connecting = true;
            try
            {
                var a = new Api(host, port);
                var info = await Task.Run(() => a.Info(3000));
                if (api != null) return;
                api = a;
                dev = info;
                beatFails = 0;
                finder.Searching = false;
                SaveLast(host);
                OnConnected();
            }
            catch { }
            finally { connecting = false; }
        }

        void ConnectManual()
        {
            var t = ipBox.Text.Trim();
            if (t.Length == 0) return;
            int port = 47820;
            var i = t.LastIndexOf(':');
            if (i > 0 && int.TryParse(t.Substring(i + 1), out var p)) { port = p; t = t.Substring(0, i); }
            waitTitle.Text = t + "에 연결하는 중…";
            _ = ConnectManualAsync(t, port);
        }

        async Task ConnectManualAsync(string host, int port)
        {
            await Connect(host, port);
            if (api == null) waitTitle.Text = "연결하지 못했어요. 앱이 켜져 있는지, 같은 와이파이인지 확인해 주세요.";
        }

        async Task TryLast()
        {
            // 마지막으로 연결했던 IP로도 직접 시도 (방화벽이 자동 찾기를 막는 경우 대비)
            while (!IsDisposed)
            {
                if (api == null)
                {
                    var last = LoadLast();
                    if (!string.IsNullOrEmpty(last)) await Connect(last, 47820);
                }
                await Task.Delay(4000);
            }
        }

        void OnConnected()
        {
            wait.Visible = false;
            lConn.Text = "연결됨: " + dev.Name + " (" + api.Host + ")";
            if (!dev.AllAccess)
                MessageBox.Show(this, "이북리더 앱에서 '모든 파일 접근 허용하기'를 눌러 권한을 켜 주세요.\n권한이 없으면 폴더가 비어 보일 수 있어요.",
                    "이북 링크", MessageBoxButtons.OK, MessageBoxIcon.Information);
            BuildTree();
            var start = cur != null && dev.Roots.Any(r => RPath.IsUnder(cur, r.Path)) ? cur : dev.Roots.FirstOrDefault()?.Path;
            if (start != null) _ = Go(start, false);
        }

        void Disconnect()
        {
            api = null;
            dev = null;
            finder.Searching = true;
            lConn.Text = "연결 안 됨";
            waitTitle.Text = "연결이 끊겼어요. 다시 찾는 중…";
            wait.Visible = true;
            wait.BringToFront();
            UpdateButtons();
        }

        async Task Heartbeat()
        {
            if (api == null || beatBusy || transferring) return;
            beatBusy = true;
            var a = api;
            try
            {
                var info = await Task.Run(() => a.Info(4000));
                if (api != a) return;
                beatFails = 0;
                dev = info;
                UpdateRootLabels();
            }
            catch
            {
                if (api == a && ++beatFails >= 2) Disconnect();
            }
            finally { beatBusy = false; }
        }

        static string LoadLast()
        {
            // 윈도우 사용자 계정으로 암호화해 둔 값 (DPAPI)
            try
            {
                var enc = File.ReadAllBytes(Path.Combine(SettingsDir, "last.bin"));
                return System.Text.Encoding.UTF8.GetString(
                    System.Security.Cryptography.ProtectedData.Unprotect(enc, null, System.Security.Cryptography.DataProtectionScope.CurrentUser)).Trim();
            }
            catch { return null; }
        }

        static void SaveLast(string host)
        {
            try
            {
                Directory.CreateDirectory(SettingsDir);
                var enc = System.Security.Cryptography.ProtectedData.Protect(
                    System.Text.Encoding.UTF8.GetBytes(host), null, System.Security.Cryptography.DataProtectionScope.CurrentUser);
                File.WriteAllBytes(Path.Combine(SettingsDir, "last.bin"), enc);
                try { File.Delete(Path.Combine(SettingsDir, "last.txt")); } catch { }   // 예전 평문 파일 정리
            }
            catch { }
        }

        // ════════════════════════ 왼쪽 폴더 트리 ════════════════════════

        void BuildTree()
        {
            tree.BeginUpdate();
            tree.Nodes.Clear();
            foreach (var r in dev.Roots)
            {
                var n = new TreeNode(RootText(r)) { Tag = r.Path, ImageKey = IconKey(null, true), SelectedImageKey = IconKey(null, true) };
                n.Nodes.Add(new TreeNode("…"));
                tree.Nodes.Add(n);
            }
            tree.EndUpdate();
        }

        static string RootText(Root r) =>
            r.Total > 0 ? r.Label + "  (" + Gb(r.Free) + " 남음)" : r.Label;

        void UpdateRootLabels()
        {
            foreach (TreeNode n in tree.Nodes)
            {
                var r = dev?.Roots.FirstOrDefault(x => x.Path == (string)n.Tag);
                if (r != null && n.Text != RootText(r)) n.Text = RootText(r);
            }
        }

        async void Tree_BeforeExpand(object sender, TreeViewCancelEventArgs e)
        {
            var n = e.Node;
            if (n.Nodes.Count != 1 || n.Nodes[0].Tag != null || api == null) return;
            e.Cancel = true;
            try
            {
                var a = api;
                var items = await Task.Run(() => a.List((string)n.Tag));
                tree.BeginUpdate();
                n.Nodes.Clear();
                foreach (var it in items.Where(i => i.IsDir && !i.Name.StartsWith(".")).OrderBy(i => i.Name, Logical))
                {
                    var c = new TreeNode(it.Name) { Tag = it.Path, ImageKey = IconKey(null, true), SelectedImageKey = IconKey(null, true) };
                    c.Nodes.Add(new TreeNode("…"));
                    n.Nodes.Add(c);
                }
                tree.EndUpdate();
                n.Expand();
            }
            catch (Exception ex) { ShowError(ex); }
        }

        void SyncTree()
        {
            // 지금 폴더가 속한 메모리(내장/외장)를 왼쪽에서 표시
            treeSync = true;
            try
            {
                TreeNode best = null;
                foreach (TreeNode n in tree.Nodes)
                    if (cur != null && RPath.IsUnder(cur, (string)n.Tag)) best = n;
                if (best != null && tree.SelectedNode != best && !(tree.SelectedNode?.Tag is string s && s == cur))
                    tree.SelectedNode = best;
            }
            finally { treeSync = false; }
        }

        void ReloadTreeNode(string dir)
        {
            var n = FindNode(tree.Nodes, dir);
            if (n == null) return;
            bool wasExpanded = n.IsExpanded;
            n.Nodes.Clear();
            n.Nodes.Add(new TreeNode("…"));
            if (wasExpanded) n.Expand();
            else n.Collapse();
        }

        static TreeNode FindNode(TreeNodeCollection nodes, string path)
        {
            foreach (TreeNode n in nodes)
            {
                var p = n.Tag as string;
                if (p == null) continue;
                if (p == path) return n;
                if (RPath.IsUnder(path, p)) return FindNode(n.Nodes, path);
            }
            return null;
        }

        // ════════════════════════ 오른쪽 목록 ════════════════════════

        async Task Go(string path, bool record = true)
        {
            if (api == null || path == null) return;
            List<Item> items;
            try
            {
                var a = api;
                items = await Task.Run(() => a.List(path));
            }
            catch (Exception ex) { ShowError(ex); return; }
            if (record && cur != null && cur != path) { back.Push(cur); fwd.Clear(); }
            cur = path;
            Fill(items, null);
            addr.Text = Display(path);
            SyncTree();
            UpdateButtons();
            list.Focus();
        }

        async Task Reload(params string[] selectNames)
        {
            if (api == null || cur == null) return;
            try
            {
                var a = api;
                var path = cur;
                var items = await Task.Run(() => a.List(path));
                if (path != cur) return;
                Fill(items, selectNames.Length > 0 ? selectNames : null);
            }
            catch (Exception ex) { ShowError(ex); }
        }

        async Task GoBack() { if (back.Count > 0) { var p = back.Pop(); fwd.Push(cur); await Go(p, false); } }
        async Task GoForward() { if (fwd.Count > 0) { var p = fwd.Pop(); back.Push(cur); await Go(p, false); } }

        async Task GoUp()
        {
            if (cur == null || IsRoot(cur)) return;
            var from = RPath.Name(cur);
            await Go(RPath.Parent(cur));
            Select(from);
        }

        bool IsRoot(string p) => dev != null && dev.Roots.Any(r => r.Path == p);

        void Fill(List<Item> items, string[] select)
        {
            var keep = select ?? Selected().Select(i => i.Name).ToArray();
            curItems = items;
            list.BeginUpdate();
            list.Items.Clear();
            foreach (var it in items)
            {
                var key = IconKey(it.Name, it.IsDir);
                var lvi = new ListViewItem(it.Name, key) { Tag = it };
                lvi.SubItems.Add(it.Mtime > 0 ? DateTimeOffset.FromUnixTimeMilliseconds(it.Mtime).LocalDateTime.ToString("yyyy-MM-dd tt h:mm", Ko) : "");
                lvi.SubItems.Add(typeNames.TryGetValue(key, out var tn) ? tn : "");
                lvi.SubItems.Add(it.IsDir ? "" : Kb(it.Size));
                list.Items.Add(lvi);
            }
            SortList();
            foreach (ListViewItem lvi in list.Items)
                if (keep.Contains(((Item)lvi.Tag).Name)) lvi.Selected = true;
            list.EndUpdate();
            lCount.Text = items.Count + "개 항목";
            UpdateButtons();
        }

        void Select(string name)
        {
            foreach (ListViewItem lvi in list.Items)
            {
                bool m = ((Item)lvi.Tag).Name == name;
                lvi.Selected = m;
                if (m) { lvi.Focused = true; lvi.EnsureVisible(); }
            }
        }

        static readonly IComparer<string> Logical = Comparer<string>.Create((a, b) => Shell.StrCmpLogicalW(a, b));

        void SortList()
        {
            list.ListViewItemSorter = new LvComparer(this);
            list.Sort();
        }

        // ListView는 제네릭이 아닌 IComparer를 원해서 감싸 줌
        class LvComparer : System.Collections.IComparer
        {
            readonly MainForm f;
            public LvComparer(MainForm f) { this.f = f; }
            public int Compare(object x, object y) => f.CompareItems((ListViewItem)x, (ListViewItem)y);
        }

        int CompareItems(ListViewItem x, ListViewItem y)
        {
            var a = (Item)x.Tag; var b = (Item)y.Tag;
            if (a.IsDir != b.IsDir) return a.IsDir ? -1 : 1;
            int c;
            switch (sortCol)
            {
                case 1: c = a.Mtime.CompareTo(b.Mtime); break;
                case 2: c = string.Compare(x.SubItems[2].Text, y.SubItems[2].Text, StringComparison.CurrentCulture); break;
                case 3: c = a.Size.CompareTo(b.Size); break;
                default: c = 0; break;
            }
            if (c == 0) c = Shell.StrCmpLogicalW(a.Name, b.Name);
            return sortDesc ? -c : c;
        }

        string IconKey(string name, bool dir)
        {
            string key = dir ? "<dir>" : Ext(name);
            if (!icons.Images.ContainsKey(key))
            {
                var bmp = Shell.Icon(dir ? "" : key, dir, out var tn);
                icons.Images.Add(key, bmp);
                typeNames[key] = tn;
            }
            return key;
        }

        static string Ext(string name)
        {
            if (name == null) return "";
            int i = name.LastIndexOf('.');
            return i > 0 ? name.Substring(i).ToLowerInvariant() : "";
        }

        List<Item> Selected() => list.SelectedItems.Cast<ListViewItem>().Select(l => (Item)l.Tag).ToList();

        string Display(string path)
        {
            var r = dev?.Roots.Where(x => RPath.IsUnder(path, x.Path)).OrderByDescending(x => x.Path.Length).FirstOrDefault();
            if (r == null) return path;
            return r.Label + path.Substring(r.Path.TrimEnd('/').Length).Replace('/', '\\');
        }

        string ParseAddr(string text)
        {
            text = text.Trim();
            if (text.StartsWith("/")) return text.TrimEnd('/');
            foreach (var r in dev?.Roots ?? new List<Root>())
            {
                if (text == r.Label) return r.Path;
                if (text.StartsWith(r.Label + "\\") || text.StartsWith(r.Label + "/"))
                    return RPath.Join(r.Path, text.Substring(r.Label.Length + 1).Replace('\\', '/').Trim('/'));
            }
            return null;
        }

        void UpdateButtons()
        {
            bool on = api != null;
            int sel = list.SelectedItems.Count;
            bBack.Enabled = on && back.Count > 0;
            bFwd.Enabled = on && fwd.Count > 0;
            bUp.Enabled = on && cur != null && !IsRoot(cur);
            bRefresh.Enabled = on;
            bNew.Enabled = on && cur != null;
            bUpload.Enabled = on && cur != null;
            bExport.Enabled = on && sel > 0;
            bDelete.Enabled = on && sel > 0;
            addr.Enabled = on;
        }

        // ════════════════════════ 키보드 · 메뉴 ════════════════════════

        void List_KeyDown(object sender, KeyEventArgs e)
        {
            if (api == null) return;
            switch (e.KeyData)
            {
                case Keys.Enter: _ = OpenSelected(); break;
                case Keys.Back: _ = GoUp(); break;
                case Keys.Delete: _ = DeleteSelected(); break;
                case Keys.F2: if (list.SelectedItems.Count == 1) list.SelectedItems[0].BeginEdit(); break;
                case Keys.Control | Keys.A: foreach (ListViewItem i in list.Items) i.Selected = true; break;
                case Keys.Control | Keys.C: CopyToClipboard(false); break;
                case Keys.Control | Keys.X: CopyToClipboard(true); break;
                case Keys.Control | Keys.V: _ = Paste(); break;
                default: return;
            }
            e.Handled = true;
            e.SuppressKeyPress = true;
        }

        void BuildMenu()
        {
            menu.Items.Clear();
            if (api == null) return;
            int sel = list.SelectedItems.Count;
            if (sel > 0)
            {
                menu.Items.Add("열기", null, (s, e) => _ = OpenSelected()).Font = new Font(Font, FontStyle.Bold);
                menu.Items.Add("PC로 내보내기…", null, (s, e) => _ = ExportPick());
                menu.Items.Add(new ToolStripSeparator());
                menu.Items.Add("잘라내기\tCtrl+X", null, (s, e) => CopyToClipboard(true));
                menu.Items.Add("복사\tCtrl+C", null, (s, e) => CopyToClipboard(false));
            }
            var paste = menu.Items.Add("붙여넣기\tCtrl+V", null, (s, e) => _ = Paste());
            paste.Enabled = CanPaste();
            menu.Items.Add(new ToolStripSeparator());
            if (sel > 0)
            {
                menu.Items.Add("삭제\tDelete", null, (s, e) => _ = DeleteSelected());
                if (sel == 1) menu.Items.Add("이름 바꾸기\tF2", null, (s, e) => list.SelectedItems[0].BeginEdit());
                menu.Items.Add(new ToolStripSeparator());
            }
            menu.Items.Add("새 폴더", null, (s, e) => _ = NewFolder());
            menu.Items.Add("PC에서 올리기…", null, (s, e) => _ = UploadPick());
            menu.Items.Add("새로고침\tF5", null, (s, e) => _ = Reload());
        }

        static bool CanPaste()
        {
            try
            {
                var d = Clipboard.GetDataObject();
                return d != null && (d.GetDataPresent(RemoteDataObject.InternalFormat) || d.GetDataPresent(DataFormats.FileDrop));
            }
            catch { return false; }
        }

        // ════════════════════════ 열기 · 새 폴더 · 이름 · 삭제 ════════════════════════

        async Task OpenSelected()
        {
            var sel = Selected();
            if (sel.Count == 0) return;
            if (sel.Count == 1 && sel[0].IsDir) { await Go(sel[0].Path); return; }
            var files = sel.Where(i => !i.IsDir).Take(10).ToList();
            var dir = NewTempDir();
            var got = await DownloadTo(files, dir, "여는 중");
            if (got == null) return;
            foreach (var f in got)
            {
                try { Process.Start(new ProcessStartInfo(f) { UseShellExecute = true }); }
                catch (Exception ex) { ShowError(ex); }
            }
        }

        async Task NewFolder()
        {
            if (api == null || cur == null) return;
            var names = new HashSet<string>(curItems.Select(i => i.Name));
            string name = "새 폴더";
            for (int n = 2; names.Contains(name); n++) name = "새 폴더 (" + n + ")";
            try
            {
                var a = api; var p = RPath.Join(cur, name);
                await Task.Run(() => a.Mkdir(p));
                await Reload(name);
                ReloadTreeNode(cur);
                var lvi = list.Items.Cast<ListViewItem>().FirstOrDefault(l => ((Item)l.Tag).Name == name);
                lvi?.BeginEdit();
            }
            catch (Exception ex) { ShowError(ex); }
        }

        async void List_AfterLabelEdit(object sender, LabelEditEventArgs e)
        {
            var it = (Item)list.Items[e.Item].Tag;
            var name = e.Label?.Trim();
            e.CancelEdit = true;   // 목록은 새로고침으로 다시 그림
            if (string.IsNullOrEmpty(name) || name == it.Name || api == null) return;
            if (name.Contains("/")) { MessageBox.Show(this, "이름에 / 는 쓸 수 없어요.", "이북 링크"); return; }
            try
            {
                var a = api; var to = RPath.Join(RPath.Parent(it.Path), name);
                await Task.Run(() => a.Rename(it.Path, to));
                await Reload(name);
                if (it.IsDir) ReloadTreeNode(cur);
            }
            catch (Exception ex) { ShowError(ex); }
        }

        async Task DeleteSelected()
        {
            var sel = Selected();
            if (sel.Count == 0 || api == null) return;
            var what = sel.Count == 1 ? "'" + sel[0].Name + "'" : sel.Count + "개 항목";
            if (MessageBox.Show(this, what + "을(를) 이북리더에서 완전히 지울까요?\n(휴지통으로 가지 않아요)", "삭제",
                    MessageBoxButtons.YesNo, MessageBoxIcon.Warning) != DialogResult.Yes) return;
            var a = api;
            await Job("지우는 중", async ct =>
            {
                int i = 0;
                foreach (var it in sel)
                {
                    ct.ThrowIfCancellationRequested();
                    pText = "지우는 중 " + (++i) + "/" + sel.Count + ": " + it.Name;
                    await Task.Run(() => a.Delete(it.Path));
                }
            });
            await Reload();
            if (sel.Any(s => s.IsDir)) ReloadTreeNode(cur);
        }

        // ════════════════════════ PC → 이북리더 (올리기) ════════════════════════

        async Task UploadPick()
        {
            if (api == null || cur == null) return;
            using (var dlg = new OpenFileDialog { Multiselect = true, Title = "이북리더로 보낼 파일 고르기" })
            {
                if (dlg.ShowDialog(this) != DialogResult.OK) return;
                await Upload(dlg.FileNames, cur);
            }
        }

        async Task Upload(IEnumerable<string> localPaths, string remoteDir)
        {
            if (api == null) return;
            var a = api;
            var paths = localPaths.Where(p => File.Exists(p) || Directory.Exists(p)).ToList();
            if (paths.Count == 0) return;

            List<Item> existing;
            try { existing = remoteDir == cur ? curItems : await Task.Run(() => a.List(remoteDir)); }
            catch (Exception ex) { ShowError(ex); return; }
            var names = new HashSet<string>(existing.Select(i => i.Name));
            var conflicts = paths.Where(p => names.Contains(LocalName(p))).ToList();
            if (conflicts.Count > 0)
            {
                var r = MessageBox.Show(this,
                    "이북리더에 같은 이름이 " + conflicts.Count + "개 있어요." +
                    (conflicts.Count == 1 ? "\n(" + LocalName(conflicts[0]) + ")" : "") +
                    "\n\n예: 덮어쓰기\n아니요: 같은 이름은 건너뛰기", "같은 이름",
                    MessageBoxButtons.YesNoCancel, MessageBoxIcon.Question);
                if (r == DialogResult.Cancel) return;
                if (r == DialogResult.No) paths = paths.Except(conflicts).ToList();
                if (paths.Count == 0) return;
            }

            await Job("올리는 중", async ct =>
            {
                pText = "보낼 파일 확인 중…";
                var dirs = new List<string>();
                var files = new List<Tuple<string, string, long>>();
                await Task.Run(() =>
                {
                    foreach (var p in paths)
                    {
                        var target = RPath.Join(remoteDir, LocalName(p));
                        if (Directory.Exists(p)) PlanUploadDir(p, target, dirs, files);
                        else files.Add(Tuple.Create(p, target, new FileInfo(p).Length));
                    }
                });
                pTotal = files.Sum(f => f.Item3);
                foreach (var d in dirs)
                {
                    ct.ThrowIfCancellationRequested();
                    await Task.Run(() => a.Mkdir(d));
                }
                int i = 0;
                foreach (var f in files)
                {
                    ct.ThrowIfCancellationRequested();
                    pText = "올리는 중 " + (++i) + "/" + files.Count + ": " + Path.GetFileName(f.Item1);
                    await Task.Run(() => a.Upload(f.Item1, f.Item2, n => Interlocked.Add(ref pDone, n), ct));
                }
            });
            if (remoteDir == cur) await Reload(paths.Select(LocalName).ToArray());
            if (paths.Any(Directory.Exists)) ReloadTreeNode(remoteDir);
        }

        static void PlanUploadDir(string local, string remote, List<string> dirs, List<Tuple<string, string, long>> files)
        {
            dirs.Add(remote);
            foreach (var f in Directory.GetFiles(local))
                files.Add(Tuple.Create(f, RPath.Join(remote, Path.GetFileName(f)), new FileInfo(f).Length));
            foreach (var d in Directory.GetDirectories(local))
                PlanUploadDir(d, RPath.Join(remote, Path.GetFileName(d)), dirs, files);
        }

        static string LocalName(string p) => Path.GetFileName(p.TrimEnd('\\', '/'));

        // ════════════════════════ 이북리더 → PC (받기) ════════════════════════

        async Task ExportPick()
        {
            var sel = Selected();
            if (sel.Count == 0 || api == null) return;
            using (var dlg = new FolderBrowserDialog { Description = "저장할 PC 폴더를 골라 주세요" })
            {
                if (dlg.ShowDialog(this) != DialogResult.OK) return;
                var got = await DownloadTo(sel, dlg.SelectedPath, "내보내는 중");
                if (got != null && got.Count > 0)
                {
                    try { Process.Start("explorer.exe", "/select,\"" + got[0] + "\""); } catch { }
                }
            }
        }

        /// <summary>이북리더 항목들을 PC 폴더로 받기. 받은 최상위 경로들을 돌려줌 (실패·취소면 null)</summary>
        async Task<List<string>> DownloadTo(List<Item> items, string localDir, string verb)
        {
            if (api == null) return null;
            var a = api;
            var result = items.Select(i => Path.Combine(localDir, SafeName(i.Name))).ToList();
            bool ok = await Job(verb, async ct =>
            {
                pText = "받을 파일 확인 중…";
                var dirs = new List<string>();
                var files = new List<Tuple<string, string, long>>();
                await Task.Run(() =>
                {
                    foreach (var it in items)
                    {
                        ct.ThrowIfCancellationRequested();
                        var target = Path.Combine(localDir, SafeName(it.Name));
                        if (it.IsDir) PlanDownloadDir(a, it.Path, target, dirs, files, ct);
                        else files.Add(Tuple.Create(it.Path, target, it.Size));
                    }
                });
                pTotal = files.Sum(f => f.Item3);
                foreach (var d in dirs) Directory.CreateDirectory(d);
                int i = 0;
                foreach (var f in files)
                {
                    ct.ThrowIfCancellationRequested();
                    pText = verb + " " + (++i) + "/" + files.Count + ": " + RPath.Name(f.Item1);
                    await Task.Run(() => a.Download(f.Item1, f.Item2, n => Interlocked.Add(ref pDone, n), ct));
                }
            });
            return ok ? result : null;
        }

        static void PlanDownloadDir(Api a, string remote, string local, List<string> dirs, List<Tuple<string, string, long>> files, CancellationToken ct)
        {
            ct.ThrowIfCancellationRequested();
            dirs.Add(local);
            foreach (var it in a.List(remote))
            {
                var target = Path.Combine(local, SafeName(it.Name));
                if (it.IsDir) PlanDownloadDir(a, it.Path, target, dirs, files, ct);
                else files.Add(Tuple.Create(it.Path, target, it.Size));
            }
        }

        static string SafeName(string name)
        {
            var bad = Path.GetInvalidFileNameChars();
            var s = new string(name.Select(c => bad.Contains(c) ? '_' : c).ToArray()).TrimEnd('.', ' ');
            return s.Length == 0 ? "_" : s;
        }

        static string NewTempDir()
        {
            var d = Path.Combine(TempRoot, DateTime.Now.ToString("HHmmss") + "-" + Guid.NewGuid().ToString("N").Substring(0, 6));
            Directory.CreateDirectory(d);
            return d;
        }

        /// <summary>끌어 놓기·붙여넣기처럼 기다려야 하는 경우: 받는 동안 화면을 살려 두며 기다림</summary>
        List<string> FetchBlocking(List<Item> items, string dir)
        {
            List<string> result = null;
            bool done = false;
            DownloadTo(items, dir, "PC로 복사 중").ContinueWith(t =>
            {
                if (t.Status == TaskStatus.RanToCompletion) result = t.Result;
                done = true;
            });
            while (!done)
            {
                Application.DoEvents();
                Thread.Sleep(15);
            }
            return result;
        }

        RemoteDataObject MakeData(List<Item> items, bool cut)
        {
            var dir = NewTempDir();
            var placeholders = items.Select(i => Path.Combine(dir, SafeName(i.Name))).ToArray();
            return new RemoteDataObject(items, placeholders, cut, () => FetchBlocking(items, dir));
        }

        // ════════════════════════ 복사 · 붙여넣기 ════════════════════════

        void CopyToClipboard(bool cut)
        {
            var sel = Selected();
            if (sel.Count == 0 || api == null) return;
            try
            {
                // copy:false → 탐색기에서 붙여넣는 순간에 파일을 받아 옴
                Clipboard.SetDataObject(MakeData(sel, cut), false);
                lProg.Text = (cut ? "잘라냄: " : "복사됨: ") + (sel.Count == 1 ? sel[0].Name : sel.Count + "개 항목") +
                             (cut ? " — 이북리더 안 다른 폴더에서 붙여넣기" : " — 이북리더 안이나 PC 탐색기에 붙여넣기");
            }
            catch (Exception ex) { ShowError(ex); }
        }

        async Task Paste()
        {
            if (api == null || cur == null) return;
            IDataObject d;
            try { d = Clipboard.GetDataObject(); } catch { return; }
            if (d == null) return;
            if (d.GetDataPresent(RemoteDataObject.InternalFormat))
            {
                var paths = ((string)d.GetData(RemoteDataObject.InternalFormat)).Split('\n').Where(p => p.Length > 0).ToList();
                bool cut = (d.GetData(RemoteDataObject.CutFormat) as string) == "1";
                await RemoteCopyMove(paths, cur, cut);
                if (cut) { try { Clipboard.Clear(); } catch { } }
            }
            else if (d.GetDataPresent(DataFormats.FileDrop))
            {
                if (d.GetData(DataFormats.FileDrop) is string[] files) await Upload(files, cur);
            }
        }

        /// <summary>이북리더 안에서 복사/이동</summary>
        async Task RemoteCopyMove(List<string> sources, string destDir, bool move)
        {
            if (api == null) return;
            var a = api;
            List<Item> existing;
            try { existing = destDir == cur ? curItems : await Task.Run(() => a.List(destDir)); }
            catch (Exception ex) { ShowError(ex); return; }
            var names = new HashSet<string>(existing.Select(i => i.Name));
            var done = new List<string>();
            var skipped = new List<string>();
            await Job(move ? "옮기는 중" : "복사 중", async ct =>
            {
                int i = 0;
                foreach (var src in sources)
                {
                    ct.ThrowIfCancellationRequested();
                    var name = RPath.Name(src);
                    pText = (move ? "옮기는 중 " : "복사 중 ") + (++i) + "/" + sources.Count + ": " + name;
                    if (RPath.IsUnder(destDir, src)) { skipped.Add(name); continue; }   // 폴더를 자기 안으로
                    if (move)
                    {
                        if (RPath.Parent(src) == destDir) continue;                     // 같은 자리
                        if (names.Contains(name)) { skipped.Add(name); continue; }
                        var to = RPath.Join(destDir, name);
                        await Task.Run(() => a.Rename(src, to));
                    }
                    else
                    {
                        var n2 = name;
                        if (names.Contains(n2))
                        {
                            var ext = Ext(name);
                            var stem = ext.Length > 0 && !existing.Any(x => x.Name == name && x.IsDir) ? name.Substring(0, name.Length - ext.Length) : name;
                            if (stem == name) ext = "";
                            n2 = stem + " - 복사본" + ext;
                            for (int k2 = 2; names.Contains(n2); k2++) n2 = stem + " - 복사본 (" + k2 + ")" + ext;
                        }
                        var to = RPath.Join(destDir, n2);
                        await Task.Run(() => a.Copy(src, to));
                        name = n2;
                    }
                    names.Add(name);
                    done.Add(name);
                }
            });
            if (skipped.Count > 0)
                MessageBox.Show(this, "같은 이름이 있거나 옮길 수 없는 위치라서 건너뛴 항목:\n" + string.Join("\n", skipped.Take(10)),
                    "이북 링크", MessageBoxButtons.OK, MessageBoxIcon.Information);
            await Reload(destDir == cur ? done.ToArray() : new string[0]);
            ReloadTreeNode(destDir);
            if (move) foreach (var p in sources.Select(RPath.Parent).Distinct()) ReloadTreeNode(p);
        }

        // ════════════════════════ 끌어 놓기 ════════════════════════

        void List_ItemDrag(object sender, ItemDragEventArgs e)
        {
            var sel = Selected();
            if (sel.Count == 0 || api == null) return;
            dragObj = MakeData(sel, false);
            dragObj.WaitForDrop = true;
            try { list.DoDragDrop(dragObj, DragDropEffects.Copy | DragDropEffects.Move); }
            finally { dragObj = null; }
        }

        /// <summary>마우스 위치의 이북리더 폴더 (목록의 폴더 항목, 트리 노드, 아니면 지금 폴더)</summary>
        string DropTarget(object sender, DragEventArgs e, out bool onItem)
        {
            onItem = false;
            if (sender == tree)
            {
                var n = tree.GetNodeAt(tree.PointToClient(new Point(e.X, e.Y)));
                onItem = n != null;
                return n?.Tag as string;
            }
            var hit = list.GetItemAt(list.PointToClient(new Point(e.X, e.Y)).X, list.PointToClient(new Point(e.X, e.Y)).Y);
            if (hit != null && ((Item)hit.Tag).IsDir) { onItem = true; return ((Item)hit.Tag).Path; }
            return cur;
        }

        void Target_DragOver(object sender, DragEventArgs e)
        {
            e.Effect = DragDropEffects.None;
            if (api == null) return;
            var target = DropTarget(sender, e, out bool onItem);
            foreach (ListViewItem l in list.Items) if (l.BackColor != list.BackColor) l.BackColor = list.BackColor;
            if (target == null) return;

            if (e.Data.GetDataPresent(RemoteDataObject.InternalFormat))
            {
                // 이북리더 안에서 옮기기: 다른 폴더 위에 놓을 때만
                var src = ((string)e.Data.GetData(RemoteDataObject.InternalFormat)).Split('\n');
                if (!onItem || src.Any(p => RPath.IsUnder(target, p)) || src.All(p => RPath.Parent(p) == target)) return;
                e.Effect = (e.KeyState & 8) != 0 ? DragDropEffects.Copy : DragDropEffects.Move;   // Ctrl 누르면 복사
            }
            else if (e.Data.GetDataPresent(DataFormats.FileDrop))
            {
                e.Effect = DragDropEffects.Copy;
            }
            else return;

            if (sender == list && onItem)
            {
                var hit = list.GetItemAt(list.PointToClient(new Point(e.X, e.Y)).X, list.PointToClient(new Point(e.X, e.Y)).Y);
                if (hit != null) hit.BackColor = SystemColors.GradientInactiveCaption;
            }
        }

        async void Target_DragDrop(object sender, DragEventArgs e)
        {
            foreach (ListViewItem l in list.Items) l.BackColor = list.BackColor;
            if (api == null) return;
            var target = DropTarget(sender, e, out _);
            if (target == null) return;
            if (e.Data.GetDataPresent(RemoteDataObject.InternalFormat))
            {
                var src = ((string)e.Data.GetData(RemoteDataObject.InternalFormat)).Split('\n').Where(p => p.Length > 0).ToList();
                bool copy = e.Effect == DragDropEffects.Copy;
                await RemoteCopyMove(src, target, !copy);
            }
            else if (e.Data.GetData(DataFormats.FileDrop) is string[] files)
            {
                // 탐색기에서 끌어온 파일: 끌기가 끝난 뒤에 올림 (탐색기가 멈춰 보이지 않게)
                BeginInvoke(new Action(() => _ = Upload(files, target)));
            }
        }

        // ════════════════════════ 작업 진행 표시 ════════════════════════

        async Task<bool> Job(string title, Func<CancellationToken, Task> work)
        {
            if (!await gate.WaitAsync(0))
            {
                lProg.Text = "앞 작업이 끝나길 기다리는 중…";
                await gate.WaitAsync();
            }
            pCts = new CancellationTokenSource();
            pTotal = 0;
            Interlocked.Exchange(ref pDone, 0);
            pText = title;
            pStart = DateTime.Now;
            transferring = true;
            prog.Value = 0;
            prog.Visible = true;
            lCancel.Visible = true;
            try
            {
                await work(pCts.Token);
                lProg.Text = "완료";
                return true;
            }
            catch (Exception) when (pCts.IsCancellationRequested)
            {
                lProg.Text = "취소했어요";
                return false;
            }
            catch (Exception ex)
            {
                lProg.Text = "실패";
                ShowError(ex);
                return false;
            }
            finally
            {
                transferring = false;
                prog.Visible = false;
                lCancel.Visible = false;
                gate.Release();
            }
        }

        void TickProgress()
        {
            if (!transferring) return;
            long done = Interlocked.Read(ref pDone), total = pTotal;
            var sb = pText ?? "";
            if (total > 0)
            {
                prog.Value = (int)Math.Min(1000, done * 1000 / total);
                var sec = (DateTime.Now - pStart).TotalSeconds;
                var speed = sec > 0.5 ? done / sec : 0;
                sb += "   " + (done * 100 / total) + "%  (" + Mb(done) + " / " + Mb(total) + ")";
                if (speed > 0) sb += "  " + Mb((long)speed) + "/s";
            }
            lProg.Text = sb;
        }

        void ShowError(Exception ex)
        {
            while (ex is AggregateException ae && ae.InnerException != null) ex = ae.InnerException;
            MessageBox.Show(this, ex.Message, "이북 링크", MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }

        // ════════════════════════ 숫자 모양 ════════════════════════

        static string Kb(long b) => (b == 0 ? 0 : Math.Max(1, (b + 1023) / 1024)).ToString("N0") + " KB";
        static string Mb(long b) => b >= 1L << 30 ? (b / (double)(1L << 30)).ToString("0.00") + " GB" : (b / (double)(1 << 20)).ToString("0.0") + " MB";
        static string Gb(long b) => (b / (double)(1L << 30)).ToString("0.0") + "GB";
    }
}
