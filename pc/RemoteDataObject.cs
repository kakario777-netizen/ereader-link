using System;
using System.Collections.Generic;
using System.Linq;
using System.Windows.Forms;

namespace EreaderLink
{
    /// <summary>
    /// 이북리더 파일을 PC 탐색기로 끌어 놓거나 붙여넣을 때 쓰는 데이터.
    /// 실제 파일은 탐색기가 달라고 하는 순간(놓는 순간)에 받아 온다.
    /// </summary>
    public class RemoteDataObject : DataObject
    {
        public const string InternalFormat = "EreaderLinkItems";
        public const string CutFormat = "EreaderLinkCut";

        readonly Func<List<string>> fetch;
        List<string> got;
        bool fetching;

        /// <summary>끌기 중에는 놓기 전까지 받지 않음</summary>
        public bool WaitForDrop;
        public bool Dropped;

        public RemoteDataObject(IEnumerable<Item> items, string[] placeholders, bool cut, Func<List<string>> fetch)
        {
            this.fetch = fetch;
            SetData(InternalFormat, string.Join("\n", items.Select(i => i.Path)));
            SetData(CutFormat, cut ? "1" : "0");
            SetData(DataFormats.FileDrop, placeholders);
        }

        public override object GetData(string format, bool autoConvert)
        {
            if (format == DataFormats.FileDrop && got == null && !fetching && (!WaitForDrop || Dropped))
            {
                fetching = true;
                try { got = fetch() ?? new List<string>(); }
                finally { fetching = false; }
                base.SetData(DataFormats.FileDrop, got.ToArray());
            }
            return base.GetData(format, autoConvert);
        }
    }
}
