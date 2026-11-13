using System;
using System.Collections.Generic;
using System.Drawing;
using System.Runtime.InteropServices;

namespace EreaderLink
{
    /// <summary>윈도우 탐색기와 같은 아이콘·파일 종류 이름 가져오기</summary>
    static class Shell
    {
        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        struct SHFILEINFO
        {
            public IntPtr hIcon;
            public int iIcon;
            public uint dwAttributes;
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 260)] public string szDisplayName;
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 80)] public string szTypeName;
        }

        [DllImport("shell32.dll", CharSet = CharSet.Unicode)]
        static extern IntPtr SHGetFileInfo(string pszPath, uint attr, ref SHFILEINFO psfi, uint cb, uint flags);

        [DllImport("user32.dll")]
        static extern bool DestroyIcon(IntPtr h);

        [DllImport("shlwapi.dll", CharSet = CharSet.Unicode)]
        public static extern int StrCmpLogicalW(string a, string b);

        const uint SHGFI_ICON = 0x100, SHGFI_SMALLICON = 0x1, SHGFI_USEFILEATTRIBUTES = 0x10, SHGFI_TYPENAME = 0x400;
        const uint ATTR_DIR = 0x10, ATTR_NORMAL = 0x80;

        static readonly Dictionary<string, string> typeCache = new Dictionary<string, string>();

        public static Bitmap Icon(string ext, bool dir, out string typeName)
        {
            var info = new SHFILEINFO();
            string name = dir ? "folder" : "file" + ext;
            SHGetFileInfo(name, dir ? ATTR_DIR : ATTR_NORMAL, ref info, (uint)Marshal.SizeOf(info),
                SHGFI_ICON | SHGFI_SMALLICON | SHGFI_USEFILEATTRIBUTES | SHGFI_TYPENAME);
            typeName = string.IsNullOrEmpty(info.szTypeName) ? (dir ? "파일 폴더" : "파일") : info.szTypeName;
            Bitmap bmp = null;
            if (info.hIcon != IntPtr.Zero)
            {
                try { using (var ic = System.Drawing.Icon.FromHandle(info.hIcon)) bmp = ic.ToBitmap(); }
                finally { DestroyIcon(info.hIcon); }
            }
            return bmp ?? new Bitmap(16, 16);
        }
    }
}
