param([string]$SourceImage)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$artworkRoot=Join-Path $projectRoot 'artwork'
$resRoot=Join-Path $projectRoot 'app\src\main\res\drawable-nodpi'
if([string]::IsNullOrWhiteSpace($SourceImage)) {$SourceImage=Join-Path $artworkRoot 'launcher-icon-source.png'}
if(!(Test-Path -LiteralPath $SourceImage -PathType Leaf)) {throw "Missing selected artwork: $SourceImage"}
New-Item -ItemType Directory -Path $artworkRoot,$resRoot -Force | Out-Null
Add-Type -AssemblyName System.Drawing
$drawingAssemblies=@([Drawing.Bitmap].Assembly.Location,[Drawing.Color].Assembly.Location)
foreach($name in @('System.Private.Windows.GdiPlus.dll','System.Private.Windows.Core.dll')) {
    $assemblyPath=Join-Path $PSHOME $name
    if(Test-Path -LiteralPath $assemblyPath) {$drawingAssemblies+=$assemblyPath}
}
Add-Type -ReferencedAssemblies $drawingAssemblies -TypeDefinition @'
using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
public static class LauncherIconExport {
    static Bitmap Resize(Bitmap source,int size) {
        var output=new Bitmap(size,size,PixelFormat.Format32bppArgb);
        using(var g=Graphics.FromImage(output)) {
            g.CompositingMode=CompositingMode.SourceCopy;
            g.InterpolationMode=InterpolationMode.HighQualityBicubic;
            g.PixelOffsetMode=PixelOffsetMode.HighQuality;
            using(var attributes=new ImageAttributes()) {
                attributes.SetWrapMode(WrapMode.TileFlipXY);
                g.DrawImage(source,new Rectangle(0,0,size,size),0,0,source.Width,source.Height,GraphicsUnit.Pixel,attributes);
            }
        }
        return output;
    }
    public static void Export(string sourcePath,string artworkPath,string icon512Path,string previewPath) {
        using(var source=new Bitmap(sourcePath)) {
            if(source.Width!=source.Height) throw new ArgumentException("Selected icon must be square");
            using(var icon512=Resize(source,512)) icon512.Save(icon512Path,ImageFormat.Png);
            // 108dp canvas at xxxhdpi; preserve selected artwork inside the central 68dp.
            // Edge extension prevents transparent seams during adaptive icon animation.
            using(var core=Resize(source,272))
            using(var adaptive=new Bitmap(432,432,PixelFormat.Format32bppArgb)) {
                for(int y=0;y<432;y++) for(int x=0;x<432;x++)
                    adaptive.SetPixel(x,y,core.GetPixel(Math.Clamp(x-80,0,271),Math.Clamp(y-80,0,271)));
                adaptive.Save(artworkPath,ImageFormat.Png);
                using(var preview=new Bitmap(1000,580,PixelFormat.Format32bppArgb))
                using(var g=Graphics.FromImage(preview))
                using(var font=new Font(FontFamily.GenericSansSerif,16)) {
                    g.Clear(Color.FromArgb(245,248,245));
                    g.SmoothingMode=SmoothingMode.AntiAlias;
                    g.InterpolationMode=InterpolationMode.HighQualityBicubic;
                    for(int i=0;i<2;i++) {
                        int left=i==0?70:550;var rect=new Rectangle(left,60,380,380);
                        using(var clip=new GraphicsPath()) {
                            if(i==0) clip.AddEllipse(rect);
                            else {
                                const int arc=110;
                                clip.AddArc(left,60,arc,arc,180,90);clip.AddArc(left+380-arc,60,arc,arc,270,90);
                                clip.AddArc(left+380-arc,440-arc,arc,arc,0,90);clip.AddArc(left,440-arc,arc,arc,90,90);clip.CloseFigure();
                            }
                            var saved=g.Save();g.SetClip(clip);
                            // Approximate the launcher's central 72dp mask, not the full 108dp layer.
                            g.DrawImage(adaptive,rect,new Rectangle(72,72,288,288),GraphicsUnit.Pixel);g.Restore(saved);
                        }
                        g.DrawString(i==0?"Circle crop":"Rounded square crop",font,Brushes.DarkSlateGray,left+65,475);
                    }
                    preview.Save(previewPath,ImageFormat.Png);
                }
            }
        }
    }
}
'@
[LauncherIconExport]::Export([IO.Path]::GetFullPath($SourceImage),(Join-Path $resRoot 'ic_launcher_artwork.png'),(Join-Path $artworkRoot 'launcher-icon-512.png'),(Join-Path $artworkRoot 'launcher-icon-preview.png'))
Get-Item (Join-Path $resRoot 'ic_launcher_artwork.png'),(Join-Path $artworkRoot 'launcher-icon-512.png'),(Join-Path $artworkRoot 'launcher-icon-preview.png') | Select-Object FullName,Length
