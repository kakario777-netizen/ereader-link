using System;
using System.Collections.Generic;
using System.IO;
using System.Net;
using System.Text;
using System.Threading;
using System.Web.Script.Serialization;

namespace EreaderLink
{
    public class Item
    {
        public string Name;
        public string Path;   // 이북리더 안의 전체 경로
        public bool IsDir;
        public long Size;
        public long Mtime;    // 1970년부터 밀리초
    }

    public class Root
    {
        public string Label, Path;
        public long Free, Total;
    }

    public class DeviceInfo
    {
        public string Name;
        public bool AllAccess;
        public List<Root> Roots = new List<Root>();
    }

    /// <summary>이북리더 앱과 통신하는 부분</summary>
    public class LinkException : Exception
    {
        public LinkException(string m) : base(m) { }
    }

    public class Api
    {
        public readonly string Host;
        public readonly int Port;
        static readonly JavaScriptSerializer Json = new JavaScriptSerializer { MaxJsonLength = int.MaxValue };

        public Api(string host, int port) { Host = host; Port = port; }

        string Base => "http://" + Host + ":" + Port;
        static string Q(string p) => "?p=" + Uri.EscapeDataString(p);

        HttpWebRequest Make(string method, string pq, int timeoutMs)
        {
            var r = (HttpWebRequest)WebRequest.Create(Base + pq);
            r.Method = method;
            r.Proxy = null;
            r.Timeout = timeoutMs;
            r.ReadWriteTimeout = Math.Max(timeoutMs, 60000);
            r.KeepAlive = true;
            return r;
        }

        static string ReadAll(WebResponse resp)
        {
            using (var sr = new StreamReader(resp.GetResponseStream(), Encoding.UTF8))
                return sr.ReadToEnd();
        }

        static Exception Wrap(WebException e)
        {
            if (e.Response is HttpWebResponse er)
            {
                try { return new LinkException(ReadAll(er)); } catch { }
            }
            return new LinkException("이북리더와 연결이 끊겼어요. (" + e.Status + ")");
        }

        string Call(string method, string pq, int timeoutMs = 15000)
        {
            var r = Make(method, pq, timeoutMs);
            if (method != "GET") r.ContentLength = 0;
            try
            {
                using (var resp = r.GetResponse()) return ReadAll(resp);
            }
            catch (WebException e) { throw Wrap(e); }
        }

        static long L(object o) => o == null ? 0 : Convert.ToInt64(o);

        public DeviceInfo Info(int timeoutMs = 5000)
        {
            var d = (Dictionary<string, object>)Json.DeserializeObject(Call("GET", "/info", timeoutMs));
            var info = new DeviceInfo
            {
                Name = d["name"] as string ?? "이북리더",
                AllAccess = d.ContainsKey("allAccess") && d["allAccess"] is bool b && b
            };
            foreach (var o in (object[])d["roots"])
            {
                var r = (Dictionary<string, object>)o;
                info.Roots.Add(new Root
                {
                    Label = (string)r["label"],
                    Path = (string)r["path"],
                    Free = L(r["free"]),
                    Total = L(r["total"])
                });
            }
            return info;
        }

        public List<Item> List(string dir)
        {
            var d = (Dictionary<string, object>)Json.DeserializeObject(Call("GET", "/list" + Q(dir)));
            var list = new List<Item>();
            foreach (var o in (object[])d["items"])
            {
                var r = (Dictionary<string, object>)o;
                var name = (string)r["n"];
                list.Add(new Item
                {
                    Name = name,
                    Path = RPath.Join(dir, name),
                    IsDir = r["d"] is bool b && b,
                    Size = L(r["s"]),
                    Mtime = L(r["m"])
                });
            }
            return list;
        }

        public void Mkdir(string p) => Call("POST", "/mkdir" + Q(p));
        public void Delete(string p) => Call("POST", "/delete" + Q(p), 600000);
        public void Rename(string p, string to) => Call("POST", "/rename" + Q(p) + "&to=" + Uri.EscapeDataString(to), 600000);
        public void Copy(string p, string to) => Call("POST", "/copy" + Q(p) + "&to=" + Uri.EscapeDataString(to), 600000);

        public void Download(string remote, string local, Action<long> onBytes, CancellationToken ct)
        {
            var r = Make("GET", "/file" + Q(remote), 15000);
            using (ct.Register(() => { try { r.Abort(); } catch { } }))
            {
                try
                {
                    using (var resp = (HttpWebResponse)r.GetResponse())
                    using (var src = resp.GetResponseStream())
                    {
                        var part = local + ".part";
                        try
                        {
                            using (var dst = new FileStream(part, FileMode.Create, FileAccess.Write, FileShare.None, 1 << 16))
                            {
                                var buf = new byte[256 * 1024];
                                int n;
                                while ((n = src.Read(buf, 0, buf.Length)) > 0)
                                {
                                    ct.ThrowIfCancellationRequested();
                                    dst.Write(buf, 0, n);
                                    onBytes(n);
                                }
                            }
                            if (File.Exists(local)) File.Delete(local);
                            File.Move(part, local);
                        }
                        catch
                        {
                            try { File.Delete(part); } catch { }
                            throw;
                        }
                        if (long.TryParse(resp.Headers["X-Mtime"], out var m) && m > 0)
                        {
                            try { File.SetLastWriteTime(local, DateTimeOffset.FromUnixTimeMilliseconds(m).LocalDateTime); } catch { }
                        }
                    }
                }
                catch (WebException e)
                {
                    ct.ThrowIfCancellationRequested();
                    throw Wrap(e);
                }
            }
        }

        public void Upload(string local, string remote, Action<long> onBytes, CancellationToken ct)
        {
            var fi = new FileInfo(local);
            long mtime = new DateTimeOffset(fi.LastWriteTimeUtc).ToUnixTimeMilliseconds();
            var r = Make("PUT", "/file" + Q(remote) + "&m=" + mtime, 30000);
            r.ContentLength = fi.Length;
            r.AllowWriteStreamBuffering = false;
            r.SendChunked = false;
            r.ContentType = "application/octet-stream";
            using (ct.Register(() => { try { r.Abort(); } catch { } }))
            {
                try
                {
                    using (var src = new FileStream(local, FileMode.Open, FileAccess.Read, FileShare.ReadWrite, 1 << 16))
                    using (var dst = r.GetRequestStream())
                    {
                        var buf = new byte[256 * 1024];
                        int n;
                        while ((n = src.Read(buf, 0, buf.Length)) > 0)
                        {
                            ct.ThrowIfCancellationRequested();
                            dst.Write(buf, 0, n);
                            onBytes(n);
                        }
                    }
                    using (var resp = r.GetResponse()) ReadAll(resp);
                }
                catch (WebException e)
                {
                    ct.ThrowIfCancellationRequested();
                    throw Wrap(e);
                }
            }
        }
    }

    /// <summary>이북리더 쪽 경로('/'로 구분) 다루기</summary>
    public static class RPath
    {
        public static string Join(string dir, string name) => dir.TrimEnd('/') + "/" + name;
        public static string Name(string p)
        {
            p = p.TrimEnd('/');
            int i = p.LastIndexOf('/');
            return i < 0 ? p : p.Substring(i + 1);
        }
        public static string Parent(string p)
        {
            p = p.TrimEnd('/');
            int i = p.LastIndexOf('/');
            return i <= 0 ? "/" : p.Substring(0, i);
        }
        public static bool IsUnder(string p, string dir) =>
            p == dir || p.StartsWith(dir.TrimEnd('/') + "/", StringComparison.Ordinal);
    }
}
