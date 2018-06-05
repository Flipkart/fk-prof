package fk.prof.nfr;

/**
 * Created by gaurav.ashok on 04/04/17.
 */
public class MatrixMultiplicationLoad {

    RndGen rnd;

    int size;

    float[][] matrix1, matrix2;

    float[][] mul;

    public MatrixMultiplicationLoad(int size, RndGen rndGen) {
        this.size = size;

        this.matrix1 = new float[size][size];
        this.matrix2 = new float[size][size];
        this.mul = new float[size][size];
        this.rnd = rndGen;
    }

    public void reset() {
        for (int i = 0; i < size; ++i) {
            for (int j = 0; j < size; ++j) {
                matrix1[i][j] = rnd.getFloat() + 1.0f;
                matrix2[i][j] = rnd.getFloat() + 1.0f;
            }
        }
    }

    public void multiply() {
        for_all_i();
    }

    private void for_all_i() {
        for (int i = 0; i < size; ++i) {
            for_all_j(i);
        }
    }

    private void for_all_j(int i) {
        for (int j = 0; j < size; ++j) {
            for_i_j(i, j);
        }
    }

    private void for_i_j(int i, int j) {
        float sum = 0.0f;
        for (int k = 0; k < size; ++k) {
            sum += matrix1[i][k] * matrix2[k][j];
        }

        mul[i][j] = sum;
    }
}
