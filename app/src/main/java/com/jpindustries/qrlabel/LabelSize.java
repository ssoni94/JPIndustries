package com.jpindustries.qrlabel;

public final class LabelSize {
    public static final LabelSize REEL_3X2_INCH = new LabelSize("3 x 2 in", 76, 51);
    public static final LabelSize BOX_4X4_INCH = new LabelSize("4 x 4 in", 102, 102);
    public static final LabelSize ROUND_4_INCH = BOX_4X4_INCH;

    public static final LabelSize[] STANDARD_SIZES = {
            new LabelSize("", 0, 0),
            new LabelSize("30 x 20 mm", 30, 20),
            new LabelSize("40 x 25 mm", 40, 25),
            new LabelSize("50 x 30 mm", 50, 30),
            new LabelSize("60 x 40 mm", 60, 40),
            new LabelSize("75 x 50 mm", 75, 50),
            REEL_3X2_INCH,
            BOX_4X4_INCH,
            new LabelSize("100 x 50 mm", 100, 50)
    };

    private final String name;
    private final int widthMm;
    private final int heightMm;

    public LabelSize(String name, int widthMm, int heightMm) {
        this.name = name;
        this.widthMm = widthMm;
        this.heightMm = heightMm;
    }

    public int getWidthMm() {
        return widthMm;
    }

    public int getHeightMm() {
        return heightMm;
    }

    public boolean isBlank() {
        return widthMm <= 0 || heightMm <= 0;
    }

    @Override
    public String toString() {
        return name;
    }
}
