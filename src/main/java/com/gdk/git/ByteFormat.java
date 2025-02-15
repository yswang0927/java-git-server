package com.gdk.git;

import java.text.DecimalFormat;
import java.text.FieldPosition;
import java.text.Format;
import java.text.ParsePosition;

/**
 * ByteFormat is a formatter which takes numbers and returns filesizes in bytes,
 * kilobytes, megabytes, or gigabytes.
 *
 * @author James Moger
 */
public class ByteFormat extends Format {

    private static final long serialVersionUID = 1L;

    public ByteFormat() {
    }

    public String format(long value) {
        return format(Long.valueOf(value));
    }

    @Override
    public StringBuffer format(Object obj, StringBuffer buf, FieldPosition pos) {
        if (obj instanceof Number) {
            long numBytes = ((Number) obj).longValue();
            if (numBytes < 1024) {
                DecimalFormat formatter = new DecimalFormat("#,##0");
                buf.append(formatter.format((double) numBytes)).append(" b");
            } else if (numBytes < 1024 * 1024) {
                DecimalFormat formatter = new DecimalFormat("#,##0");
                buf.append(formatter.format(numBytes / 1024.0)).append(" KB");
            } else if (numBytes < 1024 * 1024 * 1024) {
                DecimalFormat formatter = new DecimalFormat("#,##0.0");
                buf.append(formatter.format(numBytes / (1024.0 * 1024.0))).append(" MB");
            } else {
                DecimalFormat formatter = new DecimalFormat("#,##0.0");
                buf.append(formatter.format(numBytes / (1024.0 * 1024.0 * 1024.0))).append(" GB");
            }
        }
        return buf;
    }

    @Override
    public Object parseObject(String source, ParsePosition pos) {
        return null;
    }
}
