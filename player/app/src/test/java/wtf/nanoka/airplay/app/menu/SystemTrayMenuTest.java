package wtf.nanoka.airplay.app.menu;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.awt.Dimension;
import java.awt.Font;
import java.awt.Point;
import java.awt.Rectangle;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SystemTrayMenuTest {

    @Test
    void usesACompositeLogicalFontWhilePreservingPlatformMetrics() {
        Font menuFont = SystemTrayMenu.logicalMenuFont(new Font(Font.SERIF, Font.BOLD, 17));

        assertEquals(Font.DIALOG, menuFont.getFamily());
        assertEquals(Font.BOLD, menuFont.getStyle());
        assertEquals(17f, menuFont.getSize2D());
    }

    @Test
    void logicalMenuFontCanDisplayChineseOnWindows() {
        Assumptions.assumeTrue(System.getProperty("os.name").toLowerCase().contains("windows"));

        Font menuFont = SystemTrayMenu.logicalMenuFont(null);

        assertEquals(-1, menuFont.canDisplayUpTo("打开 显示视频窗口 全屏 语言 中文 退出"));
    }

    @Test
    void positionsTrayPopupInsideTheUsableScreen() {
        Point location = SystemTrayMenu.popupLocation(
                new Point(1910, 1070), new Rectangle(0, 0, 1920, 1040), new Dimension(200, 180));

        assertEquals(new Point(1710, 860), location);
    }
}
