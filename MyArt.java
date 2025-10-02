public class MyArt {
    public static int[][][] fillPixels(int width, int height) {
        int[][][] pixels = new int[height][width][3];
        for(int y=0; y<height; y++) {
            for(int x=0; x<width; x++) {
                pixels[y][x][0] = y * 255 / height;
                pixels[y][x][1] = x * 255 / width;
                pixels[y][x][2] = 128
            }
        }
        return pixels;
    }
}
