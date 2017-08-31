import java.io.File;

class BtPrinter {
    private native void printBt();

    public int baz() {
        printBt();
        return (int) System.currentTimeMillis();
    }

    public static void main(String[] args) {
        String path = new File("bt.so").getAbsolutePath();
        System.load(path);

        new BtBuilder().foo();
    }
}
class BtBuilder {
    void foo() {
        for (int i = 0; i < 2; i++) {
            int j = bar(i);
            System.out.println("j = " + j);
        }
    }

    private int bar(int i) {
        return i * 10 + (new BtPrinter()).baz();
    }
}

