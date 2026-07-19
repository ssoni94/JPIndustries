package com.jpindustries.qrlabel;

public final class TsplCommandBuilder {
    private static final String REEL_PRINT_FONT = "ARIAL.TTF";

    private TsplCommandBuilder() {
    }

    public static String buildSampleLabel(String qrData, String labelText) {
        return buildSampleLabel(qrData, labelText, LabelSize.STANDARD_SIZES[2]);
    }

    public static String buildSampleLabel(String qrData, String labelText, LabelSize labelSize) {
        return buildSampleLabel(qrData, labelText, labelSize, "");
    }

    public static String buildSampleLabel(String qrData, String labelText, LabelSize labelSize, String detailText) {
        return buildSampleLabel(qrData, labelText, labelSize, detailText, "");
    }

    public static String buildSampleLabel(String qrData, String labelText, LabelSize labelSize, String detailText, String footerText) {
        String safeQrData = escape(qrData);
        String safeLabelText = escape(labelText);
        String safeDetailText = escape(detailText);
        String safeFooterText = escape(footerText);
        LabelSize size = labelSize == null || labelSize.isBlank() ? LabelSize.STANDARD_SIZES[3] : labelSize;
        int widthDots = mmToDots(size.getWidthMm());
        int heightDots = mmToDots(size.getHeightMm());
        int margin = mmToDots(4);
        int qrCellSize = Math.max(3, Math.min(7, Math.min(widthDots, heightDots) / 90));
        boolean hasFooterText = !safeFooterText.trim().isEmpty();
        int labelTextY = Math.max(margin, heightDots - mmToDots(hasFooterText ? 16 : 12));
        int detailTextY = Math.max(margin, heightDots - mmToDots(hasFooterText ? 10 : 7));
        int footerTextY = Math.max(margin, heightDots - mmToDots(5));

        String command = "SIZE " + size.getWidthMm() + " mm," + size.getHeightMm() + " mm\r\n"
                + "GAP 2 mm,0\r\n"
                + "DIRECTION 1\r\n"
                + "CLS\r\n"
                + "QRCODE " + margin + "," + margin + ",L," + qrCellSize + ",A,0,\"" + safeQrData + "\"\r\n"
                + "TEXT " + margin + "," + labelTextY + ",\"3\",0,1,1,\"" + safeLabelText + "\"\r\n"
                + "TEXT " + margin + "," + detailTextY + ",\"2\",0,1,1,\"" + safeDetailText + "\"\r\n";
        if (hasFooterText) {
            command += "TEXT " + margin + "," + footerTextY + ",\"2\",0,1,1,\"" + safeFooterText + "\"\r\n";
        }
        return command + "PRINT 1\r\n";
    }

    public static String buildReelLabel(
            String swg,
            String colour,
            String grossWeight,
            String tareWeight,
            String netWeight,
            String packedBy
    ) {
        String safeSwg = escape(swg);
        String safeColour = escape(colour);
        String safeGrossWeight = escape(grossWeight);
        String safeTareWeight = escape(tareWeight);
        String safeNetWeight = escape(netWeight);
        String safePackedBy = escape(packedBy);

        int width = mmToDots(LabelSize.REEL_3X2_INCH.getWidthMm());
        int height = mmToDots(LabelSize.REEL_3X2_INCH.getHeightMm());
        String compareText = "SWG-" + safeSwg + " " + safeColour + " NET-" + safeNetWeight + "kg";
        String packedByText = "BY-" + safePackedBy;

        return "SIZE 76 mm,51 mm\r\n"
                + "GAP 2 mm,0\r\n"
                + "DIRECTION 1\r\n"
                + "CLS\r\n"
                + "BOX 0,0," + (width - 1) + "," + (height - 1) + ",2\r\n"
                + "TEXT 150,18,\"" + REEL_PRINT_FONT + "\",0,1,1,\"FONT COMPARE\"\r\n"
                + "TEXT 12,78,\"" + REEL_PRINT_FONT + "\",0,1,1,\"ARIAL: " + compareText + "\"\r\n"
                + "TEXT 12,148,\"0\",0,1,1,\"F0: " + compareText + "\"\r\n"
                + "TEXT 12,218,\"3\",0,1,1,\"F3: " + compareText + "\"\r\n"
                + "TEXT 12,330,\"2\",0,1,1,\"" + packedByText + "\"\r\n"
                + "PRINT 1\r\n";
    }

    public static String buildBoxGroupLabel(
            String qrData,
            String brand,
            String swg,
            String colour,
            String[] reelNetWeights,
            String groupNetWeight,
            LabelSize labelSize,
            String enteredBy
    ) {
        String safeQrData = escape(qrData);
        String safeBrand = escape(brand);
        String safeSwg = escape(swg);
        String safeColour = escape(colour);
        String safeGroupNetWeight = escape(groupNetWeight);
        String safeEnteredBy = escape(enteredBy);
        LabelSize size = labelSize == null || labelSize.isBlank() ? LabelSize.BOX_4X4_INCH : labelSize;
        int width = mmToDots(size.getWidthMm());
        int height = mmToDots(size.getHeightMm());
        int centerX = width / 2;

        StringBuilder command = new StringBuilder();
        command.append("SIZE ").append(size.getWidthMm()).append(" mm,").append(size.getHeightMm()).append(" mm\r\n")
                .append("GAP 2 mm,0\r\n")
                .append("DIRECTION 1\r\n")
                .append("CLS\r\n")
                .append("BOX 0,0,").append(width - 1).append(",").append(height - 1).append(",2\r\n")
                .append("BAR 0,").append(mmToDots(14)).append(",").append(width).append(",2\r\n")
                .append("BAR 0,").append(mmToDots(31)).append(",").append(width).append(",2\r\n")
                .append("BAR 0,").append(mmToDots(88)).append(",").append(width).append(",2\r\n")
                .append("TEXT ").append(centerX - mmToDots(7)).append(",").append(mmToDots(3)).append(",\"3\",0,1,1,\"").append(safeBrand).append("\"\r\n")
                .append("TEXT ").append(centerX - mmToDots(22)).append(",").append(mmToDots(8)).append(",\"3\",0,1,1,\"FINE COPPER WIRE\"\r\n")
                .append("TEXT ").append(mmToDots(6)).append(",").append(mmToDots(18)).append(",\"2\",0,1,1,\"SWG\"\r\n")
                .append("TEXT ").append(mmToDots(28)).append(",").append(mmToDots(17)).append(",\"3\",0,1,1,\"").append(safeSwg).append("\"\r\n")
                .append("TEXT ").append(mmToDots(6)).append(",").append(mmToDots(25)).append(",\"2\",0,1,1,\"COLOR\"\r\n")
                .append("TEXT ").append(mmToDots(28)).append(",").append(mmToDots(24)).append(",\"2\",0,2,1,\"").append(safeColour).append("\"\r\n")
                .append("QRCODE ").append(width - mmToDots(25)).append(",").append(mmToDots(17)).append(",L,5,A,0,\"").append(safeQrData).append("\"\r\n")
                .append("TEXT ").append(mmToDots(6)).append(",").append(mmToDots(36)).append(",\"2\",0,1,1,\"REEL NET WEIGHTS\"\r\n");

        for (int index = 0; reelNetWeights != null && index < reelNetWeights.length; index++) {
            boolean leftColumn = index < 4;
            int x = leftColumn ? mmToDots(11) : centerX + mmToDots(10);
            int y = mmToDots(44 + ((index % 4) * 9));
            command.append("TEXT ").append(x).append(",").append(y).append(",\"2\",0,1,1,\"")
                    .append(escape(reelNetWeights[index]))
                    .append(" kg")
                    .append("\"\r\n");
        }

        command.append("TEXT ").append(mmToDots(6)).append(",").append(height - mmToDots(18)).append(",\"2\",0,1,1,\"TOTAL NET WT.\"\r\n")
                .append("TEXT ").append(mmToDots(6)).append(",").append(height - mmToDots(12)).append(",\"4\",0,2,2,\"")
                .append(safeGroupNetWeight)
                .append(" kg")
                .append("\"\r\n")
                .append("TEXT ").append(centerX + mmToDots(20)).append(",").append(height - mmToDots(6)).append(",\"1\",0,1,1,\"BY-")
                .append(safeEnteredBy)
                .append("\"\r\n")
                .append("PRINT 1\r\n");
        return command.toString();
    }

    public static String buildBoxOuterSticker(
            String qrData,
            String brand,
            String swg,
            String colour,
            String netWeight,
            String date,
            String time,
            LabelSize labelSize
    ) {
        String safeQrData = escape(qrData);
        String safeBrand = escape(brand);
        String safeSwg = escape(swg);
        String safeColour = escape(colour);
        String safeNetWeight = escape(netWeight);
        LabelSize size = labelSize == null || labelSize.isBlank() ? LabelSize.STANDARD_SIZES[5] : labelSize;
        int width = mmToDots(size.getWidthMm());
        int height = mmToDots(size.getHeightMm());
        int margin = mmToDots(3);

        return "SIZE " + size.getWidthMm() + " mm," + size.getHeightMm() + " mm\r\n"
                + "GAP 2 mm,0\r\n"
                + "DIRECTION 1\r\n"
                + "CLS\r\n"
                + "BOX 0,0," + (width - 1) + "," + (height - 1) + ",2\r\n"
                + "TEXT " + margin + "," + mmToDots(3) + ",\"2\",0,1,1,\"" + safeBrand + "\"\r\n"
                + "QRCODE " + (width / 2) + "," + mmToDots(3) + ",L,4,A,0,\"" + safeQrData + "\"\r\n"
                + "TEXT " + mmToDots(10) + "," + mmToDots(12) + ",\"3\",0,1,1,\"S.W.G\"\r\n"
                + "TEXT " + mmToDots(12) + "," + mmToDots(19) + ",\"3\",0,1,1,\"" + safeSwg + "\"\r\n"
                + "TEXT " + mmToDots(12) + "," + mmToDots(26) + ",\"2\",0,1,1,\"" + safeColour + "\"\r\n"
                + "TEXT " + (width / 2 + mmToDots(6)) + "," + mmToDots(20) + ",\"3\",0,1,1,\"NET WT.\"\r\n"
                + "TEXT " + (width / 2) + "," + mmToDots(29) + ",\"5\",0,2,2,\"" + safeNetWeight + " kg\"\r\n"
                + "PRINT 1\r\n";
    }

    private static int mmToDots(int mm) {
        return Math.round(mm * 8f);
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\"", "'");
    }
}
