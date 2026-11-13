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
            ServicePointManager.DefaultConnectionLimit = 8;
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new MainForm());
        }
    }
}
