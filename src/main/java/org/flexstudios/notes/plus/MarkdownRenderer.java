package org.flexstudios.notes.plus;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Visual Markdown & Rich Text Renderer for JavaFX.
 * Renders bold, italic, underline, strikethrough, headings, colors and lists with real visual styling.
 */
final class MarkdownRenderer {

    private MarkdownRenderer() {}

    static Node render(String content, double fontSize, String defaultColorHex, double leading) {
        VBox box = new VBox(leading > 0 ? leading : 6);
        box.setPadding(new Insets(10, 0, 10, 0));
        if (content == null || content.trim().isEmpty()) {
            Label empty = new Label("Empty note — click Edit Mode to write");
            empty.getStyleClass().add("secondary");
            box.getChildren().add(empty);
            return box;
        }

        Color defColor = defaultColorHex != null && defaultColorHex.startsWith("#") ? Color.web(defaultColorHex) : Color.web("#f8fafc");

        for (String rawLine : content.split("\n", -1)) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                Region spacer = new Region();
                spacer.setMinHeight(8);
                spacer.setPrefHeight(8);
                box.getChildren().add(spacer);
                continue;
            }

            // Headings
            if (line.startsWith("# ")) {
                Text t = new Text(line.substring(2));
                t.setFont(Font.font("System", FontWeight.BOLD, fontSize + 10));
                t.setFill(defColor);
                box.getChildren().add(new TextFlow(t));
                continue;
            }
            if (line.startsWith("## ")) {
                Text t = new Text(line.substring(3));
                t.setFont(Font.font("System", FontWeight.BOLD, fontSize + 6));
                t.setFill(defColor);
                box.getChildren().add(new TextFlow(t));
                continue;
            }
            if (line.startsWith("### ")) {
                Text t = new Text(line.substring(4));
                t.setFont(Font.font("System", FontWeight.BOLD, fontSize + 3));
                t.setFill(defColor);
                box.getChildren().add(new TextFlow(t));
                continue;
            }

            // Lists
            if (line.startsWith("• ") || line.startsWith("- ") || line.startsWith("* ")) {
                String itemText = line.replaceFirst("^(?:•|[*–-])\\s+", "");
                Text bullet = new Text("•  ");
                bullet.setFont(Font.font("System", FontWeight.BOLD, fontSize));
                bullet.setFill(Color.web("#6366f1"));
                TextFlow flow = parseInline(itemText, fontSize, defColor);
                HBox row = new HBox(4, bullet, flow);
                row.setPadding(new Insets(1, 0, 1, 14));
                box.getChildren().add(row);
                continue;
            }

            // Numbered list
            Matcher numM = Pattern.compile("^(\\d+)[.)]\\s+(.*)$").matcher(line);
            if (numM.matches()) {
                Text num = new Text(numM.group(1) + ".  ");
                num.setFont(Font.font("System", FontWeight.BOLD, fontSize));
                num.setFill(Color.web("#6366f1"));
                TextFlow flow = parseInline(numM.group(2), fontSize, defColor);
                HBox row = new HBox(4, num, flow);
                row.setPadding(new Insets(1, 0, 1, 14));
                box.getChildren().add(row);
                continue;
            }

            // Regular paragraph line
            TextFlow flow = parseInline(line, fontSize, defColor);
            box.getChildren().add(flow);
        }

        return box;
    }

    private static TextFlow parseInline(String text, double baseSize, Color defColor) {
        TextFlow flow = new TextFlow();
        Pattern p = Pattern.compile("(\\*\\*(.*?)\\*\\*)|(\\*(.*?)\\*)|(<u>(.*?)</u>)|(~~(.*?)~~)|(<font color=[\"'](.*?)[\"']>(.*?)</font>)");
        Matcher m = p.matcher(text);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) {
                Text normal = new Text(text.substring(last, m.start()));
                normal.setFont(Font.font("System", FontWeight.NORMAL, FontPosture.REGULAR, baseSize));
                normal.setFill(defColor);
                flow.getChildren().add(normal);
            }
            if (m.group(1) != null) { // **bold**
                Text b = new Text(m.group(2));
                b.setFont(Font.font("System", FontWeight.BOLD, FontPosture.REGULAR, baseSize));
                b.setFill(defColor);
                flow.getChildren().add(b);
            } else if (m.group(3) != null) { // *italic*
                Text it = new Text(m.group(4));
                it.setFont(Font.font("System", FontWeight.NORMAL, FontPosture.ITALIC, baseSize));
                it.setFill(defColor);
                flow.getChildren().add(it);
            } else if (m.group(5) != null) { // <u>underline</u>
                Text u = new Text(m.group(6));
                u.setFont(Font.font("System", FontWeight.NORMAL, FontPosture.REGULAR, baseSize));
                u.setUnderline(true);
                u.setFill(defColor);
                flow.getChildren().add(u);
            } else if (m.group(7) != null) { // ~~strike~~
                Text s = new Text(m.group(8));
                s.setFont(Font.font("System", FontWeight.NORMAL, FontPosture.REGULAR, baseSize));
                s.setStrikethrough(true);
                s.setFill(defColor);
                flow.getChildren().add(s);
            } else if (m.group(9) != null) { // font color
                Text c = new Text(m.group(11));
                c.setFont(Font.font("System", FontWeight.NORMAL, FontPosture.REGULAR, baseSize));
                try {
                    c.setFill(Color.web(m.group(10)));
                } catch (Exception ex) {
                    c.setFill(defColor);
                }
                flow.getChildren().add(c);
            }
            last = m.end();
        }
        if (last < text.length()) {
            Text normal = new Text(text.substring(last));
            normal.setFont(Font.font("System", FontWeight.NORMAL, FontPosture.REGULAR, baseSize));
            normal.setFill(defColor);
            flow.getChildren().add(normal);
        }
        return flow;
    }
}
