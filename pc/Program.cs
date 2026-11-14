using System;
using System.Net;
using System.Windows.Forms;

namespace EreaderLink
{
    static class Program
    {
        [STAThread]
        static void Main()
        {
            ServicePointManager.Expect100Continue = false;
            ServicePointManager.SecurityProtocol |= SecurityProtocolType.Tls12;   // GitHub 접속용
            ServicePointManager.DefaultConnectionLimit = 8;
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new MainForm());
        }
    }
}
