package com.jpindustries.qrlabel;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public final class TsplBitmapEncoder {
    private static final int DOTS_PER_MM = 8;

    private TsplBitmapEncoder() {
    }

    public static int dotsForMm(int mm) {
        return mm * DOTS_PER_MM;
    }

    public static byte[] buildBitmapLabel(Bitmap bitmap, LabelSize labelSize) {
        int widthBytes = (bitmap.getWidth() + 7) / 8;
        int height = bitmap.getHeight();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeAscii(output, "SIZE " + labelSize.getWidthMm() + " mm," + labelSize.getHeightMm() + " mm\r\n");
        writeAscii(output, "GAP 2 mm,0\r\n");
        writeAscii(output, "DIRECTION 1\r\n");
        writeAscii(output, "CLS\r\n");
        writeAscii(output, "BITMAP 0,0," + widthBytes + "," + height + ",0,");
        output.writeBytes(toMonoRaster(bitmap, widthBytes));
        writeAscii(output, "\r\nPRINT 1\r\n");
        return output.toByteArray();
    }

    private static byte[] toMonoRaster(Bitmap bitmap, int widthBytes) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        byte[] data = new byte[widthBytes * height];
        for (int y = 0; y < height; y++) {
            int rowOffset = y * widthBytes;
            for (int x = 0; x < width; x++) {
                int pixel = bitmap.getPixel(x, y);
                int luminance = (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000;
                if (luminance >= 180) {
                    data[rowOffset + (x / 8)] |= (byte) (0x80 >> (x % 8));
                }
            }
        }
        return data;
    }

    private static void writeAscii(ByteArrayOutputStream output, String text) {
        output.writeBytes(text.getBytes(StandardCharsets.US_ASCII));
    }
}
