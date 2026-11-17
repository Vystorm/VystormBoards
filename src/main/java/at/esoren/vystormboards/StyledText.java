package at.esoren.vystormboards;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;

/** MiniMessage text flattened into coloured runs per line, ready to be drawn with Java2D. */
public final class StyledText {
    /** A piece of one line with one style. {@code rgb} is 0xRRGGBB. */
    public record Run(String text, int rgb, boolean bold, boolean italic, boolean underlined) {}

    private StyledText() {}

    public static List<List<Run>> parse(String miniMessage, int defaultRgb) {
        List<List<Run>> lines = new ArrayList<>();
        lines.add(new ArrayList<>());
        walk(MiniMessage.miniMessage().deserialize(miniMessage.replace("\\n", "<br>")), Style.empty(), defaultRgb, lines);
        return lines;
    }

    private static void walk(Component component, Style inherited, int defaultRgb, List<List<Run>> lines) {
        Style style = component.style().merge(inherited, Style.Merge.Strategy.IF_ABSENT_ON_TARGET);
        if (component instanceof TextComponent text && !text.content().isEmpty()) {
            TextColor color = style.color();
            int rgb = color == null ? defaultRgb : color.value();
            boolean bold = style.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE;
            boolean italic = style.decoration(TextDecoration.ITALIC) == TextDecoration.State.TRUE;
            boolean underlined = style.decoration(TextDecoration.UNDERLINED) == TextDecoration.State.TRUE;
            String[] parts = text.content().split("\n", -1);
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) lines.add(new ArrayList<>());
                if (!parts[i].isEmpty()) lines.getLast().add(new Run(parts[i], rgb, bold, italic, underlined));
            }
        }
        for (Component child : component.children()) walk(child, style, defaultRgb, lines);
    }

    /** Plain text of one line (for measuring and tests). */
    public static String plain(List<Run> line) {
        StringBuilder out = new StringBuilder();
        for (Run run : line) out.append(run.text());
        return out.toString();
    }
}
