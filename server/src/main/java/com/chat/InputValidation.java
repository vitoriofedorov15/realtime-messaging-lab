package com.chat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** Bounded byte/header checks; no pixel decoding or disk access. */
final class InputValidation {
    static final int MAX_IMAGE_BYTES = 1024 * 1024;
    static final int MAX_AVATAR_BYTES = 200 * 1024;
    static final int MAX_IMAGE_SIDE = 4096;
    static final long MAX_IMAGE_PIXELS = 16_000_000;

    private InputValidation() {}

    static void name(String name) {
        if (name.isEmpty() || name.codePointCount(0, name.length()) > 50
                || !name.matches("[\\p{L}\\p{Nd}_-]+")) {
            throw new IllegalArgumentException("Name must contain 1–50 letters, digits, _ or -");
        }
    }

    static void message(String text, boolean hasImage) {
        if (text.codePointCount(0, text.length()) > 25) {
            throw new IllegalArgumentException("Message must contain at most 25 characters");
        }
        if (text.codePoints().allMatch(cp -> Character.isWhitespace(cp) || Character.isSpaceChar(cp)) && !hasImage) {
            throw new IllegalArgumentException("Message needs text or an image");
        }
    }

    static void image(byte[] bytes, String mime, int limit) {
        if (bytes.length == 0 || bytes.length > limit) {
            throw new IllegalArgumentException("Image must contain 1–" + limit + " bytes");
        }
        boolean png = bytes.length >= 8 && bytes[0] == (byte) 137 && bytes[1] == 80
                && bytes[2] == 78 && bytes[3] == 71 && bytes[4] == 13 && bytes[5] == 10
                && bytes[6] == 26 && bytes[7] == 10;
        boolean jpeg = bytes.length >= 3 && bytes[0] == (byte) 255
                && bytes[1] == (byte) 216 && bytes[2] == (byte) 255;
        if (!(png && mime.equals("image/png") || jpeg && mime.equals("image/jpeg"))) {
            throw new IllegalArgumentException("Only JPEG/PNG with a matching MIME type are accepted");
        }
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IllegalArgumentException("Invalid image header");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width > MAX_IMAGE_SIDE || height > MAX_IMAGE_SIDE
                        || (long) width * height > MAX_IMAGE_PIXELS) {
                    throw new IllegalArgumentException("Image exceeds 4096 pixels per side or 16 million pixels");
                }
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid image header");
        }
    }
}
