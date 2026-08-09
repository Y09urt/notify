package com.yogurt.notify;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;

public final class MessageFormatter {
    private static final int COLOR_TEXT = 0xFF111827;
    private static final int COLOR_DANGER = 0xFFDC2626;
    private static final int COLOR_PRIMARY = 0xFF2563EB;

    private MessageFormatter() {
    }

    public static CharSequence format(String value) {
        SpannableStringBuilder output = new SpannableStringBuilder();
        String text = value == null ? "" : value;
        String[] lines = text.split("\\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                output.append('\n');
            }
            appendLine(output, lines[i], true);
        }
        return output;
    }

    public static String plainText(String value) {
        SpannableStringBuilder output = new SpannableStringBuilder();
        String text = value == null ? "" : value;
        String[] lines = text.split("\\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                output.append('\n');
            }
            appendLine(output, lines[i], false);
        }
        return output.toString();
    }

    private static void appendLine(SpannableStringBuilder output, String line, boolean styled) {
        if (line.startsWith("# ")) {
            int start = output.length();
            appendInline(output, line.substring(2), styled);
            if (styled) {
                output.setSpan(new StyleSpan(Typeface.BOLD), start, output.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                output.setSpan(new RelativeSizeSpan(1.08f), start, output.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                output.setSpan(new ForegroundColorSpan(COLOR_TEXT), start, output.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            return;
        }

        if (line.startsWith("- ")) {
            output.append("• ");
            appendInline(output, line.substring(2), styled);
            return;
        }

        appendInline(output, line, styled);
    }

    private static void appendInline(SpannableStringBuilder output, String text, boolean styled) {
        int index = 0;
        while (index < text.length()) {
            if (text.startsWith("**", index)) {
                int end = text.indexOf("**", index + 2);
                if (end >= 0) {
                    appendSpan(output, text.substring(index + 2, end), styled, InlineStyle.BOLD);
                    index = end + 2;
                    continue;
                }
            }

            if (text.startsWith("!!", index)) {
                int end = text.indexOf("!!", index + 2);
                if (end >= 0) {
                    appendSpan(output, text.substring(index + 2, end), styled, InlineStyle.DANGER);
                    index = end + 2;
                    continue;
                }
            }

            if (text.charAt(index) == '`') {
                int end = text.indexOf('`', index + 1);
                if (end >= 0) {
                    appendSpan(output, text.substring(index + 1, end), styled, InlineStyle.CODE);
                    index = end + 1;
                    continue;
                }
            }

            output.append(text.charAt(index));
            index++;
        }
    }

    private static void appendSpan(
            SpannableStringBuilder output,
            String text,
            boolean styled,
            InlineStyle style
    ) {
        int start = output.length();
        output.append(text);
        if (!styled || text.isEmpty()) {
            return;
        }

        if (style == InlineStyle.BOLD) {
            output.setSpan(new StyleSpan(Typeface.BOLD), start, output.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else if (style == InlineStyle.DANGER) {
            output.setSpan(new StyleSpan(Typeface.BOLD), start, output.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            output.setSpan(new ForegroundColorSpan(COLOR_DANGER), start, output.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else if (style == InlineStyle.CODE) {
            output.setSpan(new TypefaceSpan("monospace"), start, output.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            output.setSpan(new ForegroundColorSpan(COLOR_PRIMARY), start, output.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private enum InlineStyle {
        BOLD,
        DANGER,
        CODE
    }
}
