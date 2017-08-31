package fk.prof.recorder.main;

import java.util.Map;

/**
 * @understands burning CPU in intrinsics
 */
public class IntrinsicBurner {
    private final double[] nos;
    public volatile long timeDelta = 0;
    public static volatile double val = 0;

    public IntrinsicBurner(double[] nos) {
        this.nos = nos;
    }

    public static void main(String[] args) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : System.getenv().entrySet()) {
            sb.append(e.getKey()).append(e.getValue());
        }
        String s = sb.toString();
        double[] nos = new double[s.length() * 10];
        for (int i = 0; i < s.length(); i++) {
            for (int j = 0; j < 10; j++) {
                nos[i + j] = s.charAt(i) * j;
            }
        }

        IntrinsicBurner burner = new IntrinsicBurner(nos);
        for (int i = 0; i < 10; i++) {
            burner.burn();
        }
    }

    private void burn() {
        long start = System.nanoTime();
        int l = nos.length;
        double[] value = new double[l];
        System.arraycopy(nos, 0, value, 0, l);
        for (int times = 0; times < 10; times++) {
            for (int i = 0; i < nos.length; i++) {
                for (int j = i; j < nos.length; j++) {
                    double q = 0.9 * i * times;
                    if (j % 2 == 0) {
                        value[i] += Math.sin(value[j]) + Math.sin(nos[j]) + Math.sin(j % 10) + Math.sin(q) + Math.cos(j * q) + Math.sin(value[l - j - 1]);
                    } else {
                        value[i] += Math.cos(value[j]) + Math.cos(nos[j]) + Math.cos(j % 10) + Math.cos(q) + Math.sin(j * q) + Math.cos(value[l - j - 1]);
                    }
                    value[i] = Math.cos(Math.sin(value[i]));
                }
                val += (nos[l - i - 1] = value[i]);
            }
        }
        System.out.println("time = " + ((System.nanoTime() - start) / 1000000));
    }
}
