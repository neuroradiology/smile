/*******************************************************************************
 * Copyright (c) 2010-2020 Haifeng Li. All rights reserved.
 *
 * Smile is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * Smile is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Smile.  If not, see <https://www.gnu.org/licenses/>.
 ******************************************************************************/

package smile.manifold;

import java.util.Collection;
import java.util.stream.IntStream;

import smile.graph.AdjacencyList;
import smile.graph.Graph.Edge;
import smile.math.DifferentiableMultivariateFunction;
import smile.math.LevenbergMarquardt;
import smile.math.MathEx;
import smile.math.distance.Distance;
import smile.math.distance.EuclideanDistance;
import smile.math.matrix.DenseMatrix;
import smile.math.matrix.EVD;
import smile.math.matrix.SparseMatrix;
import smile.netlib.ARPACK;
import smile.stat.distribution.GaussianDistribution;

/**
 * Uniform Manifold Approximation and Projection.
 *
 * UMAP is a dimension reduction technique that can be used for visualization
 * similarly to t-SNE, but also for general non-linear dimension reduction.
 * The algorithm is founded on three assumptions about the data:
 *
 * <ol>
 * <li>The data is uniformly distributed on a Riemannian manifold;</li>
 * <li>The Riemannian metric is locally constant (or can be approximated as
 * such);</li>
 * <li>The manifold is locally connected.</li>
 * </ol>
 *
 * From these assumptions it is possible to model the manifold with a fuzzy
 * topological structure. The embedding is found by searching for a low
 * dimensional projection of the data that has the closest possible equivalent
 * fuzzy topological structure.
 *
 * <h2>References</h2>
 * <ol>
 * <li>McInnes, L, Healy, J, UMAP: Uniform Manifold Approximation and Projection for Dimension Reduction, ArXiv e-prints 1802.03426, 2018</li>
 * <li>How UMAP Works: https://umap-learn.readthedocs.io/en/latest/how_umap_works.html</li>
 * </ol>
 *
 * @see TSNE
 *
 * @author rayeaster
 */
public class UMAP {
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(UMAP.class);

    /**
     * The coordinate matrix in embedding space.
     */
    public final double[][] coordinates;

    /**
     * Constructor.
     * @param coordinates the coordinates.
     */
    public UMAP(double[][] coordinates) {
        this.coordinates = coordinates;
    }

    /**
     * Runs the UMAP algorithm.
     *
     * @param data the input data.
     */
    public static UMAP of(double[][] data) {
        return of(data, 15);
    }

    /**
     * Runs the UMAP algorithm.
     *
     * @param data     the input data.
     * @param distance the distance measure.
     */
    public static <T> UMAP of(T[] data, Distance<T> distance) {
        return of(data, distance, 15);
    }

    /**
     * Runs the UMAP algorithm.
     *
     * @param data    the input data.
     * @param k       k-nearest neighbors. Larger values result in more global views
     *                of the manifold, while smaller values result in more local data
     *                being preserved. Generally in the range 2 to 100.
     */
    public static UMAP of(double[][] data, int k) {
        return of(data, new EuclideanDistance(), k);
    }

    /**
     * Runs the UMAP algorithm.
     *
     * @param data    the input data.
     * @param k       k-nearest neighbor. Larger values result in more global views
     *                of the manifold, while smaller values result in more local data
     *                being preserved. Generally in the range 2 to 100.
     */
    public static <T> UMAP of(T[] data, Distance<T> distance, int k) {
        return of(data, distance, k, 2, data.length > 10000 ? 200 : 500, 1.0, 0.1, 1.0, 5, 1.0);
    }

    /**
     * Runs the UMAP algorithm.
     *
     * @param data               the input data.
     * @param k                  k-nearest neighbors. Larger values result in more global views
     *                           of the manifold, while smaller values result in more local data
     *                           being preserved. Generally in the range 2 to 100.
     * @param d                  The target embedding dimensions. defaults to 2 to provide easy
     *                           visualization, but can reasonably be set to any integer value
     *                           in the range 2 to 100.
     * @param iterations         The number of iterations to optimize the
     *                           low-dimensional representation. Larger values result in more
     *                           accurate embedding. Muse be at least 10, choose wise value
     *                           based on the size of the input data, e.g, 200 for large
     *                           data (10000+ samples), 500 for small.
     * @param learningRate       The initial learning rate for the embedding optimization,
     *                           default 1.
     * @param minDist            The desired separation between close points in the embedding
     *                           space. Smaller values will result in a more clustered/clumped
     *                           embedding where nearby points on the manifold are drawn closer
     *                           together, while larger values will result on a more even
     *                           disperse of points. The value should be set no-greater than
     *                           and relative to the spread value, which determines the scale
     *                           at which embedded points will be spread out. default 0.1.
     * @param spread             The effective scale of embedded points. In combination with
     *                           minDist, this determines how clustered/clumped the embedded
     *                           points are. default 1.0.
     * @param negativeSamples    The number of negative samples to select per positive sample
     *                           in the optimization process. Increasing this value will result
     *                           in greater repulsive force being applied, greater optimization
     *                           cost, but slightly more accuracy, default 5.
     * @param repulsionStrength  Weighting applied to negative samples in low dimensional
     *                           embedding optimization. Values higher than one will result in
     *                           greater weight being given to negative samples, default 1.0.
     */
    public static UMAP of(double[][] data, int k, int d, int iterations, double learningRate, double minDist, double spread, int negativeSamples, double repulsionStrength) {
        return of(data, new EuclideanDistance(), k, d, iterations, learningRate, minDist, spread, negativeSamples, repulsionStrength);
    }

    /**
     * Runs the UMAP algorithm.
     *
     * @param data               the input data.
     * @param distance           the distance measure.
     * @param k                  k-nearest neighbor. Larger values result in more global views
     *                           of the manifold, while smaller values result in more local data
     *                           being preserved. Generally in the range 2 to 100.
     * @param d                  The target embedding dimensions. defaults to 2 to provide easy
     *                           visualization, but can reasonably be set to any integer value
     *                           in the range 2 to 100.
     * @param iterations         The number of iterations to optimize the
     *                           low-dimensional representation. Larger values result in more
     *                           accurate embedding. Muse be at least 10, choose wise value
     *                           based on the size of the input data, e.g, 200 for large
     *                           data (1000+ samples), 500 for small.
     * @param learningRate       The initial learning rate for the embedding optimization,
     *                           default 1.
     * @param minDist            The desired separation between close points in the embedding
     *                           space. Smaller values will result in a more clustered/clumped
     *                           embedding where nearby points on the manifold are drawn closer
     *                           together, while larger values will result on a more even
     *                           disperse of points. The value should be set no-greater than
     *                           and relative to the spread value, which determines the scale
     *                           at which embedded points will be spread out. default 0.1.
     * @param spread             The effective scale of embedded points. In combination with
     *                           minDist, this determines how clustered/clumped the embedded
     *                           points are. default 1.0.
     * @param negativeSamples    The number of negative samples to select per positive sample
     *                           in the optimization process. Increasing this value will result
     *                           in greater repulsive force being applied, greater optimization
     *                           cost, but slightly more accuracy, default 5.
     * @param repulsionStrength  Weighting applied to negative samples in low dimensional
     *                           embedding optimization. Values higher than one will result in
     *                           greater weight being given to negative samples, default 1.0.
     */
    public static <T> UMAP of(T[] data, Distance<T> distance, int k, int d, int iterations, double learningRate, double minDist, double spread, int negativeSamples, double repulsionStrength) {
        if (d < 2) {
            throw new IllegalArgumentException("d must be greater than 1: " + d);
        }
        if (k < 2) {
            throw new IllegalArgumentException("k must be greater than 1: " + k);
        }
        if (minDist <= 0) {
            throw new IllegalArgumentException("minDist must greater than 0: " + minDist);
        }
        if (minDist > spread) {
            throw new IllegalArgumentException("minDist must be less than or equal to spread: " + minDist + ",spread=" + spread);
        }
        if (iterations < 10) {
            throw new IllegalArgumentException("epochs must be a positive integer of at least 10: " + iterations);
        }
        if (learningRate <= 0) {
            throw new IllegalArgumentException("learningRate must greater than 0: " + learningRate);
        }
        if (negativeSamples <= 0) {
            throw new IllegalArgumentException("negativeSamples must greater than 0: " + negativeSamples);
        }

        // Construct the local fuzzy simplicial set by locally approximating
        // geodesic distance at each point, and then combining all the local
        // fuzzy simplicial sets into a global one via a fuzzy union.
        AdjacencyList nng = NearestNeighborGraph.of(data, distance, k, true,null);

        int n = data.length;
        // The smooth approximator to knn-distance
        double[] sigma = new double[n];
        // The distance to nearest neighbor
        double[] rho = new double[n];

        smoothKnnDistance(nng, k, sigma, rho, 64);
        // The matrix entry (i, j) is the membership strength
        // between the i-th and j-th samples.
        Strength strength = computeMembershipStrengths(nng, sigma, rho);

        // Spectral embedding initialization
        double[][] coordinates = spectralLayout(strength.laplacian, d);

        // parameters for the differentiable curve used in lower
        // dimensional fuzzy simplicial complex construction.
        double[] curve = fitCurve(spread, minDist);

        // Optimizing the embedding
        SparseMatrix epochs = computeEpochPerSample(strength.conorm, iterations);
        optimizeLayout(coordinates, curve, epochs, iterations, learningRate, negativeSamples, repulsionStrength);
        return new UMAP(coordinates);
    }

    /**
     * The curve function:
     *
     * <pre>
     * 1.0 / (1.0 + a * x ^ (2 * b))
     * </pre>
     */
    private static DifferentiableMultivariateFunction func = new DifferentiableMultivariateFunction() {

        @Override
        public double f(double[] x) {
            return 1 / (1 + x[0] * Math.pow(x[2], x[1]));
        }

        @Override
        public double g(double[] x, double[] g) {
            double pow = Math.pow(x[2], x[1]);
            double de = 1 + x[0] * pow;
            g[0] = -pow / (de * de);
            g[1] = -(x[0] * x[1] * Math.log(x[2]) * pow) / (de * de);
            return 1 / de;
        }
    };

    /**
     * Fits the differentiable curve used in lower dimensional
     * fuzzy simplicial complex construction. We want the smooth curve (from a
     * pre-defined family with simple gradient) that best matches an offset
     * exponential decay.
     *
     * @param spread  The effective scale of embedded points. In combination with
     *                minDist, this determines how clustered/clumped the embedded
     *                points are. default 1.0
     * @param minDist The desired separation between close points in the embedding
     *                space. The value should be set no-greater than and relative to
     *                the spread value, which determines the scale at which embedded
     *                points will be spread out, default 0.1
     * @return the parameters of differentiable curve.
     */
    private static double[] fitCurve(double spread, double minDist) {
        int size = 300;
        double[] x = new double[size];
        double[] y = new double[size];
        double end = 3 * spread;
        double interval = end / size;
        for (int i = 0; i < x.length; i++) {
            x[i] = (i + 1) * interval;
            y[i] = x[i] < minDist ? 1 : Math.exp(-(x[i] - minDist) / spread);
        }
        double[] p = {0.5, 0.0};
        LevenbergMarquardt curveFit = LevenbergMarquardt.fit(func, x, y, p);
        return curveFit.p;
    }

    /**
     * Computes a continuous version of the distance to the kth nearest neighbor.
     * That is, this is similar to knn-distance but allows continuous k values
     * rather than requiring an integral k. In essence we are simply computing
     * the distance such that the cardinality of fuzzy set we generate is k.
     *
     * @param nng        The nearest neighbor graph.
     * @param k          k-nearest neighbor.
     * @param sigma      The smooth approximator to knn-distance
     * @param rho        The distance to nearest neighbor
     * @param iterations The max number of iterations of the binary search for the correct distance value.
     *                   default 64
     */
    private static void smoothKnnDistance(AdjacencyList nng, int k, double[] sigma, double[] rho, int iterations) {
        double target = MathEx.log2(k);

        int n = nng.getNumVertices();
        double avg = IntStream.range(0, n).mapToObj(i -> nng.getEdges(i))
                .flatMapToDouble(edges -> edges.stream().mapToDouble(edge -> edge.weight))
                .filter(w -> !MathEx.isZero(w, 1E-10))
                .average().orElse(0.0);

        for (int i = 0; i < n; i++) {
            double lo = 0.0;
            double hi = Double.MAX_VALUE;
            double mid = 1.0;

            Collection<Edge> knn = nng.getEdges(i);
            rho[i] = knn.stream()
                    .mapToDouble(edge -> edge.weight)
                    .filter(w -> !MathEx.isZero(w, 1E-10))
                    .min().orElse(0);

            for (int iter = 0; iter < iterations; iter++) {
                double psum = 0.0;
                for (Edge edge : knn) {
                    if (!MathEx.isZero(edge.weight, 1E-10)) {
                        double d = edge.weight - rho[i];
                        psum += Math.exp(-(d / mid));
                    }
                }

                if (Math.abs(psum - target) < 1E-5) {
                    break;
                }
                // Given that it is a parameterized function
                // and the whole thing is monotonic
                // a simply binary search is actually quite efficient.
                if (psum > target) {
                    hi = mid;
                    mid = (lo + hi) / 2.0;
                } else {
                    lo = mid;
                    if (hi == Double.MAX_VALUE) {
                        mid *= 2;
                    } else {
                        mid = (lo + hi) / 2.0;
                    }
                }
            }

            sigma[i] = mid;

            if (rho[i] > 0.0) {
                double avgi = knn.stream()
                        .mapToDouble(edge -> edge.weight)
                        .filter(w -> !MathEx.isZero(w, 1E-10))
                        .average().orElse(0.0);
                sigma[i] = Math.max(sigma[i], 1E-3 * avgi);
            } else {
                sigma[i] = Math.max(sigma[i], 1E-3 * avg);
            }
        }
    }

    private static class Strength {
        SparseMatrix conorm;
        SparseMatrix laplacian;
    }

    /**
     * Construct the membership strength data for the 1-skeleton of each local
     * fuzzy simplicial set.
     *
     * @param nng       The nearest neighbor graph.
     * @param sigma     The smooth approximator to knn-distance
     * @param rho       The distance to nearest neighbor
     * @return A fuzzy simplicial set represented as a sparse matrix. The (i, j)
     * entry of the matrix represents the membership strength of the
     * 1-simplex between the ith and jth sample points.
     */
    private static Strength computeMembershipStrengths(AdjacencyList nng, double[] sigma, double[] rho) {
        int n = nng.getNumVertices();

        for (int i = 0; i < n; i++) {
            for (Edge edge : nng.getEdges(i)) {
                edge.weight = Math.exp(-Math.max(0.0, (edge.weight - rho[i])) / (sigma[i]));
            }
        }

        // probabilistic t-conorm: (a + a' - a .* a')
        double[] D = new double[n];
        for (int i = 0; i < n; i++) {
            for (Edge edge : nng.getEdges(i)) {
                double w = edge.weight;
                double w2 = nng.getWeight(edge.v2, edge.v1); // weight of reverse arc.
                w = w + w2 - w * w2;
                edge.weight = w;
                D[i] += w;
            }

            D[i] = 1.0 / Math.sqrt(D[i]);
        }

        Strength g = new Strength();
        g.conorm = nng.toMatrix();

        for (int i = 0; i < n; i++) {
            for (Edge edge : nng.getEdges(i)) {
                edge.weight = -D[i] * edge.weight * D[edge.v2];
            }
            nng.setWeight(i, i, 1.0);
        }

        // Laplacian of graph.
        SparseMatrix laplacian = nng.toMatrix();
        laplacian.setSymmetric(true);
        g.laplacian =  laplacian;
        return g;
    }

    /**
     * Computes the spectral embedding of the graph, which is
     * the eigenvectors of the (normalized) Laplacian of the graph.
     *
     * @param L The Laplacian of graph.
     * @param d The dimension of the embedding space.
     */
    private static double[][] spectralLayout(SparseMatrix L, int d) {
        int n = L.ncols();
        // ARPACK may not find all needed eigen values for k = d + 1.
        // Set it to 10 * (d + 1) as a hack to NCV parameter of DSAUPD.
        // Our Lanczos class has no such issue.
        EVD eigen = ARPACK.eigen(L, Math.min(10*(d+1), n-1), "SM");

        double absMax = 0;
        DenseMatrix V = eigen.getEigenVectors();
        double[][] coordinates = new double[n][d];
        for (int j = d; --j >= 0; ) {
            int c = V.ncols() - j - 2;
            for (int i = 0; i < n; i++) {
                double x = V.get(i, c);
                coordinates[i][j] = x;

                double abs = Math.abs(x);
                if (abs > absMax) {
                    absMax = abs;
                }
            }
        }

        // We add a little noise to avoid local minima for optimization to come
        double expansion = 10.0 / absMax;
        GaussianDistribution gaussian = new GaussianDistribution(0.0, 0.0001);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < d; j++) {
                coordinates[i][j] = coordinates[i][j] * expansion + gaussian.rand();
            }
        }

        // normalization
        double[] colMax = MathEx.colMax(coordinates);
        double[] colMin = MathEx.colMin(coordinates);
        double[] de = new double[d];
        for (int j = 0; j < d; j++) {
            de[j] = (colMax[j] - colMin[j]);
        }

        for (int i = 0; i < n; i++) {
            for (int j = 0; j < d; j++) {
                coordinates[i][j] = 10 * (coordinates[i][j] - colMin[j]) / de[j];
            }
        }

        return coordinates;
    }

    /**
     * Improve an embedding using stochastic gradient descent to minimize the
     * fuzzy set cross entropy between the 1-skeletons of the high dimensional
     * and low dimensional fuzzy simplicial sets. In practice this is done by
     * sampling edges based on their membership strength (with the (1-p) terms
     * coming from negative sampling similar to word2vec).
     *
     * @param embedding          The embeddings to be optimized
     * @param curve              The curve parameters
     * @param epochsPerSample    The number of epochs per 1-simplex between
     *                           (ith, jth) data points. 1-simplices with weaker membership
     *                           strength will have more epochs between being sampled.
     * @param negativeSamples    The number of negative samples (with membership strength 0).
     * @param initialAlpha       The initial learning rate for the SGD
     * @param gamma              THe weight of negative samples
     * @param iterations         The number of iterations.
     */
    private static void optimizeLayout(double[][] embedding, double[] curve,
                                       SparseMatrix epochsPerSample, int iterations,
                                       double initialAlpha, int negativeSamples,
                                       double gamma) {

        int n2 = embedding.length;
        double a = curve[0];
        double b = curve[1];
        double alpha = initialAlpha;

        for (int iter = 1; iter <= iterations; iter++) {
            int i = iter;
            epochsPerSample.nonzeros().forEach(epoch -> {
                int j = epoch.i;
                int k = epoch.j;
                double prob = epoch.x;
                if (prob <= i) {
                    double[] current = embedding[j];
                    double[] other = embedding[k];

                    double distSquared = MathEx.squaredDistance(current, other);
                }
            });
            /*
            for (Entry<Integer, Map<Integer, Double>> ent : epochsPerSample.entrySet()) {
                int j = ent.getKey();
                for (Entry<Integer, Double> samp : ent.getValue().entrySet()) {
                    int k = samp.getKey();
                    double prob = getDoubleValueFrom(epochNextSample, j, k);
                    if (prob <= n) {
                        double[] current = embedding[j];
                        double[] other = embedding[k];

                        double distSquared = metric.d(current, other);
                        double gradCoeff = 0;
                        if (distSquared > 0.0) {
                            gradCoeff = -2.0 * a * b * Math.pow(distSquared, b - 1.0);
                            gradCoeff /= a * Math.pow(distSquared, b) + 1.0;
                        } else {
                            gradCoeff = 0.0;
                        }

                        double gradD = 0;
                        for (int d = 0; d < dim; d++) {
                            gradD = clamp(gradCoeff * (current[d] - other[d]));
                            current[d] += gradD * alpha;
                            other[d] += -gradD * alpha;
                        }
                        epochNextSample.get(j).put(k, samp.getValue() + getDoubleValueFrom(epochNextSample, j, k));

                        // negative sampling
                        int nNegSamples = (int) ((n - epochNextNegativeSample.get(j).get(k)) / epochPerNegativeSample.get(j).get(k));
                        for (int p = 0; p < nNegSamples; p++) {
                            k = MathEx.randomInt(Integer.MAX_VALUE) % N;

                            other = embedding[k];

                            distSquared = metric.d(current, other);

                            if (distSquared > 0.0) {
                                gradCoeff = 2.0 * gamma * b;
                                gradCoeff /= (0.001 + distSquared) * (a * Math.pow(distSquared, b) + 1);
                            } else if (j == k) {
                                continue;
                            } else {
                                gradCoeff = 0.0;
                            }

                            for (int d = 0; d < dim; d++) {
                                if (gradCoeff > 0.0) {
                                    gradD = clamp(gradCoeff * (current[d] - other[d]));
                                } else {
                                    gradD = 4.0;
                                }
                                current[d] += gradD * alpha;
                            }
                        }

                        epochNextNegativeSample.get(j).put(k, getDoubleValueFrom(epochNextNegativeSample, j, k)
                                + getDoubleValueFrom(epochPerNegativeSample, j, k) * nNegSamples);
                    }
                }
            }
             */

            alpha = initialAlpha * (1.0 - (double) iter / iterations);
        }
    }

    /**
     * Computes the number of epochs per sample, one for each 1-simplex.
     *
     * @param strength   The strength matrix.
     * @param iterations The number of iterations.
     * @return An array of number of epochs per sample, one for each 1-simplex
     * between (ith, jth) sample point.
     */
    private static SparseMatrix computeEpochPerSample(SparseMatrix strength, int iterations) {
        double max = strength.nonzeros().mapToDouble(w -> w.x).max().orElse(0.0);
        double min = max / iterations;
        strength.nonzeros().forEach(w -> {
            if (w.x < min) w.update(0.0);
            else w.update(max / w.x);
        });
        return strength;
    }

    /**
     * Clamps a value to range [-4.0, 4.0].
     */
    private static double clamp(double val) {
        if (val > 4.0) {
            return 4.0;
        } else if (val < -4.0) {
            return -4.0;
        } else {
            return val;
        }
    }
}