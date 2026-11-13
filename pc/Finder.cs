using System;
using System.Collections.Generic;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Web.Script.Serialization;

namespace EreaderLink
{
    /// <summary>같은 와이파이에서 이북리더를 자동으로 찾기</summary>
    public class Finder : IDisposable
    {
        const int DiscoveryPort = 47821, AnnouncePort = 47822;
        public event Action<string, int, string> Found;   // ip, port, 이름
        public volatile bool Searching = true;
        volatile bool stop;
        UdpClient listen, probe;

        public void Start()
        {
            try
            {
                listen = new UdpClient();
                listen.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
                listen.Client.Bind(new IPEndPoint(IPAddress.Any, AnnouncePort));
                Run(() => RecvLoop(listen));
            }
            catch { listen = null; }
            try
            {
                probe = new UdpClient(0) { EnableBroadcast = true };
                Run(() => RecvLoop(probe));
                Run(SendLoop);
            }
            catch { }
        }

        static void Run(ThreadStart f) => new Thread(f) { IsBackground = true }.Start();

        void RecvLoop(UdpClient c)
        {
            while (!stop)
            {
                try
                {
                    var from = new IPEndPoint(IPAddress.Any, 0);
                    var data = c.Receive(ref from);
                    var s = Encoding.UTF8.GetString(data);
                    if (!Searching || !s.StartsWith("EREADER_LINK!")) continue;
                    var d = (Dictionary<string, object>)new JavaScriptSerializer().DeserializeObject(s.Substring(13));
                    int port = d.ContainsKey("port") ? Convert.ToInt32(d["port"]) : 47820;
                    string name = d.ContainsKey("name") ? d["name"] as string : "이북리더";
                    Found?.Invoke(from.Address.ToString(), port, name);
                }
                catch (ObjectDisposedException) { return; }
                catch { Thread.Sleep(200); }
            }
        }

        void SendLoop()
        {
            var msg = Encoding.UTF8.GetBytes("EREADER_LINK?");
            while (!stop)
            {
                if (Searching)
                {
                    foreach (var a in Broadcasts())
                    {
                        try { probe.Send(msg, msg.Length, new IPEndPoint(a, DiscoveryPort)); } catch { }
                    }
                }
                Thread.Sleep(2000);
            }
        }

        static List<IPAddress> Broadcasts()
        {
            var list = new List<IPAddress> { IPAddress.Broadcast };
            try
            {
                foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
                {
                    if (ni.OperationalStatus != OperationalStatus.Up) continue;
                    if (ni.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
                    foreach (var ua in ni.GetIPProperties().UnicastAddresses)
                    {
                        if (ua.Address.AddressFamily != AddressFamily.InterNetwork || ua.IPv4Mask == null) continue;
                        var ip = ua.Address.GetAddressBytes();
                        var mask = ua.IPv4Mask.GetAddressBytes();
                        if (mask.Length != 4) continue;
                        var b = new byte[4];
                        for (int i = 0; i < 4; i++) b[i] = (byte)(ip[i] | ~mask[i]);
                        var addr = new IPAddress(b);
                        if (!list.Contains(addr)) list.Add(addr);
                    }
                }
            }
            catch { }
            return list;
        }

        public void Dispose()
        {
            stop = true;
            try { listen?.Close(); } catch { }
            try { probe?.Close(); } catch { }
        }
    }
}
