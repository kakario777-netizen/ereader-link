using System;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Reflection;
using System.Text.RegularExpressions;
using System.Threading;
using System.Web.Script.Serialization;
using System.Collections.Generic;
using System.Windows.Forms;

namespace EreaderLink
{
    /// <summary>
    /// 자체 업데이트: GitHub 최신 빌드를 확인해 새 exe를 받고, 지금 exe와 바꿔 끼운 뒤 다시 켬.
    /// (실행 중인 exe도 이름은 바꿀 수 있어서, 옛 파일은 .old로 비켜 두고 다음 실행 때 지움)
    /// </summary>
    static class Updater
    {
        // 업데이트 파일은 내 저장소 릴리스에서만 HTTPS로 받음
        const string Allowed = "https://github.com/kakario777-netizen/ereader-link/releases/download/";
        const string Api = "https://api.github.com/repos/kakario777-netizen/ereader-link/releases/tags/latest";

        public class Release { public int Build; public string Url; public long Size; }

        public static int Current => Assembly.GetExecutingAssembly().GetName().Version.Build;

        static HttpWebRequest Make(string url)
        {
            var r = (HttpWebRequest)WebRequest.Create(url);
            r.UserAgent = "ereader-link-pc";
            r.Accept = "application/vnd.github+json, application/octet-stream";
            r.AllowAutoRedirect = true;
            r.Timeout = 15000;
            r.ReadWriteTimeout = 30000;
            return r;
        }

        /// <summary>새 버전이 있으면 돌려줌, 없거나 확인 실패면 null</summary>
        public static Release Check()
        {
            try
            {
                string text;
                using (var resp = Make(Api).GetResponse())
                using (var sr = new StreamReader(resp.GetResponseStream()))
                    text = sr.ReadToEnd();
                var d = (Dictionary<string, object>)new JavaScriptSerializer { MaxJsonLength = int.MaxValue }.DeserializeObject(text);
                var m = Regex.Match(d["name"] as string ?? "", "#(\\d+)");
                if (!m.Success) return null;
                int build = int.Parse(m.Groups[1].Value);
                if (build <= Current) return null;
                foreach (var o in (object[])d["assets"])
                {
                    var a = (Dictionary<string, object>)o;
                    var name = a["name"] as string ?? "";
                    var url = a["browser_download_url"] as string ?? "";
                    if (name.EndsWith(".exe", StringComparison.OrdinalIgnoreCase) && url.StartsWith(Allowed, StringComparison.Ordinal))
                        return new Release { Build = build, Url = url, Size = Convert.ToInt64(a["size"]) };
                }
            }
            catch { }
            return null;
        }

        /// <summary>받아서 바꿔 끼우기. 끝나면 새 exe를 켬 (호출한 쪽은 바로 종료)</summary>
        public static void Apply(Release r, Action<long> onBytes, CancellationToken ct)
        {
            var exe = Application.ExecutablePath;
            var tmp = exe + ".new";
            var req = Make(r.Url);
            using (ct.Register(() => { try { req.Abort(); } catch { } }))
            {
                try
                {
                    using (var resp = req.GetResponse())
                    using (var src = resp.GetResponseStream())
                    using (var dst = File.Create(tmp))
                    {
                        var buf = new byte[128 * 1024];
                        int n;
                        while ((n = src.Read(buf, 0, buf.Length)) > 0)
                        {
                            ct.ThrowIfCancellationRequested();
                            dst.Write(buf, 0, n);
                            onBytes(n);
                        }
                    }
                }
                catch (WebException)
                {
                    try { File.Delete(tmp); } catch { }
                    ct.ThrowIfCancellationRequested();
                    throw new Exception("새 버전을 받다가 끊겼어요. 다시 시도해 주세요.");
                }
            }
            if (r.Size > 0 && new FileInfo(tmp).Length != r.Size)
            {
                try { File.Delete(tmp); } catch { }
                throw new Exception("새 버전 파일이 덜 받아졌어요. 다시 시도해 주세요.");
            }
            var old = exe + ".old";
            try
            {
                if (File.Exists(old)) File.Delete(old);
                File.Move(exe, old);
                File.Move(tmp, exe);
            }
            catch (Exception e)
            {
                try { if (!File.Exists(exe) && File.Exists(old)) File.Move(old, exe); } catch { }
                throw new Exception("프로그램 파일을 바꾸지 못했어요. exe를 바탕화면이나 문서 폴더처럼 쓰기 가능한 곳에 두고 실행해 주세요.\n(" + e.Message + ")");
            }
            Process.Start(new ProcessStartInfo(exe) { UseShellExecute = true, WorkingDirectory = Path.GetDirectoryName(exe) });
        }

        /// <summary>지난 업데이트에서 남은 옛 파일 지우기</summary>
        public static void Cleanup()
        {
            try
            {
                var old = Application.ExecutablePath + ".old";
                // 옛 프로그램이 완전히 꺼질 때까지 잠깐 기다리며 몇 번 시도
                for (int i = 0; i < 20 && File.Exists(old); i++)
                {
                    try { File.Delete(old); } catch { Thread.Sleep(500); }
                }
            }
            catch { }
        }
    }
}
