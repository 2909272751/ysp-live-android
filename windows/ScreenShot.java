import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** 全屏截图（用 JDK 自带 java.awt.Robot，避免依赖 PowerShell 的 .NET 程序集）。 */
public final class ScreenShot {
    public static void main(String[] args) throws Exception {
        String out = args.length > 0 ? args[0] : "shot.png";
        Rectangle screen = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        BufferedImage img = new Robot().createScreenCapture(screen);
        ImageIO.write(img, "png", new File(out));
        System.out.println("saved " + out + " " + img.getWidth() + "x" + img.getHeight());
    }
}
