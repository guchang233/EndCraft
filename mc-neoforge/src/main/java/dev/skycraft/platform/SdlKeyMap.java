package dev.skycraft.platform;
/** SDL physical scancode to GLFW logical key, for the bridge's US keyboard layout. */
public final class SdlKeyMap {
    public static int toGlfw(int sc) {
        if (sc >= 4 && sc <= 29) return 65 + sc - 4;
        if (sc >= 30 && sc <= 38) return 49 + sc - 30;
        if (sc >= 58 && sc <= 69) return 290 + sc - 58;
        if (sc >= 104 && sc <= 115) return 302 + sc - 104;
        return switch (sc) {
            case 39 -> 48; case 40 -> 257; case 41 -> 256; case 42 -> 259; case 43 -> 258; case 44 -> 32;
            case 45 -> 45; case 46 -> 61; case 47 -> 91; case 48 -> 93; case 49 -> 92; case 51 -> 59;
            case 52 -> 39; case 53 -> 96; case 54 -> 44; case 55 -> 46; case 56 -> 47; case 57 -> 280;
            case 70 -> 283; case 71 -> 281; case 72 -> 284; case 73 -> 260; case 74 -> 268; case 75 -> 266;
            case 76 -> 261; case 77 -> 269; case 78 -> 267; case 79 -> 262; case 80 -> 263; case 81 -> 264; case 82 -> 265;
            case 83 -> 282; case 84 -> 331; case 85 -> 332; case 86 -> 333; case 87 -> 334; case 88 -> 335;
            case 89,90,91,92,93,94,95,96,97 -> 321 + sc - 89; case 98 -> 320; case 99 -> 330;
            case 224 -> 341; case 225 -> 340; case 226 -> 342; case 227 -> 343;
            case 228 -> 345; case 229 -> 344; case 230 -> 346; case 231 -> 347;
            default -> -1;
        };
    }
    public static int mouseButton(int sdl) { return switch (sdl) { case 1 -> 0; case 2 -> 2; case 3 -> 1; default -> sdl >= 4 ? sdl - 1 : -1; }; }
}
