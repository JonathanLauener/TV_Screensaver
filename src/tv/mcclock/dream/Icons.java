package tv.mcclock.dream;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

import java.util.HashMap;

/**
 * 16 x 16 pixel-art icons for the menu entries, in the style of Minecraft's items (drawn for
 * this app, not taken from the game). Each row is a string, one character per pixel: '.' is
 * transparent, any other character a colour from {@link #PALETTE}.
 */
final class Icons {

    static final String TV = "tv", TV_OFF = "tv_off", NETWORK = "network", SOUND = "sound",
            APPS = "apps", CLOCK = "clock", POWER = "power", INPUTS = "inputs", INFO = "info",
            SETTINGS = "settings";

    private static final String PALETTE = "K101010 H3C3C3C G6E6E6E g9A9A9A WF0F0F0 wCDEBFF "
            + "B1E4E9A b3D7FD0 YE8B830 yFFE27A O9A6A10 D1C2440 d4F86C8 RC03030 rEE6A6A N2E9A35 "
            + "n6ED36F L2A55C0 l6D95F0 ZE8C020 zFFF19A S20242A s3A4048";
    private static final int SIZE = 16;

    private static final HashMap<String, String[]> ART = new HashMap<>();
    private static final HashMap<String, Bitmap> BITMAPS = new HashMap<>();
    private static final Paint PIXELS = new Paint();  // no filtering: hard pixel edges
    private static final Rect DST = new Rect();

    /** Draws an icon into the square at (x, y), size x size screen pixels. */
    static void draw(Canvas canvas, String name, int x, int y, int size) {
        Bitmap b = bitmap(name);
        if (b == null) return;
        DST.set(x, y, x + size, y + size);
        canvas.drawBitmap(b, null, DST, PIXELS);
    }

    private static Bitmap bitmap(String name) {
        Bitmap b = BITMAPS.get(name);
        if (b != null) return b;
        String[] rows = ART.get(name);
        if (rows == null) return null;
        HashMap<Character, Integer> colors = new HashMap<>();
        for (String entry : PALETTE.split(" ")) {
            colors.put(entry.charAt(0), 0xFF000000 | Integer.parseInt(entry.substring(1), 16));
        }
        b = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                Integer c = colors.get(rows[y].charAt(x));
                if (c != null) b.setPixel(x, y, c);
            }
        }
        BITMAPS.put(name, b);
        return b;
    }

    private static void put(String name, String... rows) {
        ART.put(name, rows);
    }

    static {
        put(TV,
                "................",
                "KKKKKKKKKKKKKKKK",
                "KgggggggggggggGK",
                "KgKKKKKKKKKKKKGK",
                "KgKwwbbbbbbbbKGK",
                "KgKwbbbbbbbbbKGK",
                "KgKbbbbbbbbbbKGK",
                "KgKbbbbbbbbbbKGK",
                "KgKbbbbbbbbbBKGK",
                "KgKbbbbbbbbBBKGK",
                "KgKKKKKKKKKKKKGK",
                "KGGGGGGGGGGGGrGK",
                "KKKKKKKKKKKKKKKK",
                "......KGGK......",
                "....KKKKKKKK....",
                "................");
        // A port with nothing on it: the screen is dark and the light is off.
        put(TV_OFF,
                "................",
                "KKKKKKKKKKKKKKKK",
                "KgggggggggggggGK",
                "KgKKKKKKKKKKKKGK",
                "KgKssSSSSSSSSKGK",
                "KgKsSSSSSSSSSKGK",
                "KgKSSSSSSSSSSKGK",
                "KgKSSSSSSSSSSKGK",
                "KgKSSSSSSSSSSKGK",
                "KgKSSSSSSSSSSKGK",
                "KgKKKKKKKKKKKKGK",
                "KGGGGGGGGGGGGHGK",
                "KKKKKKKKKKKKKKKK",
                "......KGGK......",
                "....KKKKKKKK....",
                "................");
        put(NETWORK,
                "................",
                "................",
                "....KKKKKKKK....",
                "..KKWWWWWWWWKK..",
                ".KWWKKKKKKKKWWK.",
                "KWKK........KKWK",
                ".K...KKKKKK...K.",
                "....KWWWWWWK....",
                "...KWKKKKKKWK...",
                "....K......K....",
                "......KKKK......",
                ".....KWWWWK.....",
                "......KWWK......",
                ".......KK.......",
                "................",
                "................");
        put(SOUND,
                "................",
                ".......KK.......",
                "......KWK...KK..",
                ".....KWWK..KWK..",
                ".KKKKWWWK...KWK.",
                ".KggKWWWK.K..KWK",
                ".KggKWWWKKWK.KWK",
                ".KggKWWWK.KWKKWK",
                ".KGGKWWWK.KWKKWK",
                ".KGGKWWWKKWK.KWK",
                ".KKKKWWWK.K..KWK",
                ".....KWWK...KWK.",
                "......KWK..KWK..",
                ".......KK...KK..",
                "................",
                "................");
        put(APPS,
                "................",
                ".KKKKKK..KKKKKK.",
                ".KrrrRK..KnnnNK.",
                ".KrRRRK..KnNNNK.",
                ".KrRRRK..KnNNNK.",
                ".KRRRRK..KNNNNK.",
                ".KKKKKK..KKKKKK.",
                "................",
                "................",
                ".KKKKKK..KKKKKK.",
                ".KlllLK..KzzzZK.",
                ".KlLLLK..KzZZZK.",
                ".KlLLLK..KzZZZK.",
                ".KLLLLK..KZZZZK.",
                ".KKKKKK..KKKKKK.",
                "................");
        put(CLOCK,
                "................",
                ".....KKKKKK.....",
                "...KKyyyyyyKK...",
                "..KyYddddddYOK..",
                "..KYddddddddOK..",
                ".KyddddWddddOOK.",
                ".KYddddWddddDOK.",
                ".KYddddWWWWDDOK.",
                ".KYDDDDDDDDDDOK.",
                ".KYDDDDDDDDDDOK.",
                "..KYDDDDDDDDOK..",
                "..KYYDDDDDDOOK..",
                "...KKOOOOOOKK...",
                ".....KKKKKK.....",
                "................",
                "................");
        put(POWER,
                "................",
                ".........KKKKK..",
                "........KzZZK...",
                ".......KzZZK....",
                "......KzZZK.....",
                ".....KzZZKKKKK..",
                "....KzZZZZZZK...",
                "...KKKKKzZZK....",
                ".......KzZK.....",
                "......KzZK......",
                ".....KzZK.......",
                "....KzZK........",
                "....KZK.........",
                "....KK..........",
                "................",
                "................");
        put(INPUTS,
                "................",
                "..KKKKKKKKKKKK..",
                "..KggggggggggK..",
                "..KgKKKKKKKKGK..",
                "..KgKyYyYyYKGK..",
                "..KgKKKKKKKKGK..",
                "...KgGGGGGGGK...",
                "....KKKKKKKK....",
                ".....KHGGHK.....",
                "......KGGK......",
                "......KGHK......",
                "......KHGK......",
                "......KGHK......",
                "......KHGK......",
                "......KGHK......",
                "................");
        put(INFO,
                "................",
                ".....KKKKKK.....",
                "...KKllllllKK...",
                "..KllllllllLLK..",
                "..KlllWWllllLK..",
                ".KllllWWlllllLK.",
                ".KlllllllllllLK.",
                ".KllllWWlllllLK.",
                ".KllllWWlllllLK.",
                ".KllllWWlllllLK.",
                "..KlllWWllllLK..",
                "..KLlWWWWllLLK..",
                "...KKLLLLLLKK...",
                ".....KKKKKK.....",
                "................",
                "................");
        put(SETTINGS,
                "................",
                "......KKKK......",
                "...KK.KggK.KK...",
                "..KgGKKggKKgGK..",
                "..KgggggggggGK..",
                "...KggGKKGgGK...",
                ".KKKggK..KGGKKK.",
                ".KgggK....KGGGK.",
                ".KgGGK....KGGGK.",
                ".KKKGGK..KGGKKK.",
                "...KGGGKKGGGK...",
                "..KgGGGGGGGGHK..",
                "..KgHKKGGKKGHK..",
                "...KK.KGHK.KK...",
                "......KKKK......",
                "................");
    }

    private Icons() {
    }
}
