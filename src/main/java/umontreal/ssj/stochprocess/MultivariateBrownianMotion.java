/*
 * Class:        MultivariateBrownianMotion
 * Description:
 * Environment:  Java
 * Software:     SSJ
 * Copyright (C) 2001  Pierre L'Ecuyer and Universite de Montreal
 * Organization: DIRO, Universite de Montreal
 * @author       Pierre L'Écuyer, Jean-Sébastien Parent & Clément Teule
 * @since        2008

 * SSJ is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License (GPL) as published by the
 * Free Software Foundation, either version 3 of the License, or
 * any later version.

 * SSJ is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * A copy of the GNU General Public License is available at
   <a href="http://www.gnu.org/licenses">GPL licence site</a>.
 */
package umontreal.ssj.stochprocess;
import umontreal.ssj.rng.*;
import umontreal.ssj.util.DMatrix;
import umontreal.ssj.probdist.*;
import umontreal.ssj.randvar.*;
import cern.colt.matrix.DoubleMatrix2D;
import cern.colt.matrix.impl.DenseDoubleMatrix2D;
import cern.colt.matrix.linalg.CholeskyDecomposition;

/**
 * This class represents a multivariate Brownian motion process
 * @f$\{\mathbf{X}(t) = (X_1(t),…, X_c(t)),  t \geq0 \}@f$, sampled at times
 * @f$0 = t_0 < t_1 < \cdots< t_d@f$. Each vector coordinate is a univariate
 * Brownian motion @f$\{X_i(t),  t \geq0 \}@f$, with drift and volatility
 * parameters @f$\mu_i@f$ and @f$\sigma_i@f$, so it can be written as
 * @anchor REF_stochprocess_MultivariateBrownianMotion_eq_Brownian_motion_sequential_multi
 * @f[
 *   X_i(t_j) - X_i(t_{j-1}) = (t_j - t_{j-1})\mu_i + \sqrt{t_j - t_{j-1}} \sigma_i Z_{j,i} \tag{Brownian-motion-sequential-multi}
 * @f]
 * where @f$X_i(0)=0@f$, each @f$Z_{j,i} \sim N(0,1)@f$, and each
 * @f$\mathbf{Z}_j = (Z_{j,1},…,Z_{j,c})@f$ has correlation matrix
 * @f$\mathbf{R}_z@f$. We write @f$\boldsymbol{\mu}=
 * (\mu_1,…,\mu_c)^{\mathsf{t}}@f$, @f$\boldsymbol{\sigma}=
 * (\sigma_1,…,\sigma_c)^{\mathsf{t}}@f$, and @f$\boldsymbol{\Sigma}@f$ for
 * the covariance matrix of @f$\mathbf{X}(1)-\mathbf{X}(0)@f$, which equals
 * @f$\boldsymbol{\Sigma}=
 * \boldsymbol{\sigma}\mathbf{R}_z\boldsymbol{\sigma}^{\mathsf{t}}@f$ (so the
 * element @f$(k,l)@f$ or @f$\boldsymbol{\Sigma}@f$ is the element
 * @f$(k,l)@f$ of @f$\mathbf{R}_z@f$ multiplied by @f$\sigma_k\sigma_l@f$).
 * The trajectories are sampled by the sequential (or random walk) method.
 *
 * <div class="SSJ-bigskip"></div><div class="SSJ-bigskip"></div>
 */
public class MultivariateBrownianMotion extends MultivariateStochasticProcess {
    protected NormalGen             gen;
    protected double[]              mu;
    protected double[]              sigma;
    protected double[][]            corrZ;          // Correlation matrix for Z.
    protected DoubleMatrix2D        covZ;           // Covariance Matrix
    protected DoubleMatrix2D        covZCholDecomp; // Matrix obtained using the cholesky decomposition on covZ
    protected CholeskyDecomposition decomp;
    protected boolean               covZiSCholDecomp;

    // Precomputed values for standard BM
    protected double[]     dt, sqrdt;
    protected MultivariateBrownianMotion() {};

    /**
     * Constructs a new `MultivariateBrownianMotion` with parameters
     * @f$\boldsymbol{\mu}= \mathtt{mu}@f$, @f$\boldsymbol{\sigma}=
     * \mathtt{sigma}@f$, correlation matrix @f$\mathbf{R}_z =
     * \mathtt{corrZ}@f$, and initial value @f$\mathbf{X}(t_0) =
     * \mathtt{x0}@f$. The normal variates @f$Z_j@f$ in are generated by
     * inversion using the  @ref umontreal.ssj.rng.RandomStream `stream`.
     */
    public MultivariateBrownianMotion (int c, double[] x0, double[] mu,
                                       double[] sigma, double[][] corrZ,
                                       RandomStream stream) {
        setParams(c, x0, mu, sigma, corrZ);
        this.gen   =  new NormalGen (stream, new NormalDist());
    }

    /**
     * Constructs a new `MultivariateBrownianMotion` with parameters
     * @f$\boldsymbol{\mu}= \mathtt{mu}@f$, @f$\boldsymbol{\sigma}=
     * \mathtt{sigma}@f$, correlation matrix @f$\mathbf{R}_z =
     * \mathtt{corrZ}@f$, and initial value @f$\mathbf{X}(t_0) =
     * \mathtt{x0}@f$. The normal variates @f$Z_j@f$ in are generated by
     * `gen`.
     */
    public MultivariateBrownianMotion (int c, double[] x0, double[] mu,
                                       double[] sigma, double[][] corrZ,
                                       NormalGen gen) {
        setParams(c, x0, mu, sigma, corrZ);
        this.gen   = gen;
    }

    /**
     * Generates and returns in `obs` the next observation
     * @f$\mathbf{X}(t_j)@f$ of the multivariate stochastic process. The
     * processe is sampled *sequentially*, i.e. if the last observation
     * generated was for time @f$t_{j-1}@f$, the next observation
     * returned will be for time @f$t_j@f$.
     */
    public void nextObservationVector (double[] obs)  {
        if(!covZiSCholDecomp)  // the cholesky factorisation must be done to use the matrix covZCholDecomp
            initCovZCholDecomp();

        double z;
        for (int i=0; i<c; i++) {
            z = 0.0;
            for (int k=0; k<c; k++)
                z += covZCholDecomp.getQuick(i,k) * gen.nextDouble();
            obs[i] = path[c * observationIndex + i] + mu[i] * dt[observationIndex]
                + sqrdt[observationIndex] * z;
            path[c * (observationIndex+1) + i] = obs[i];
        }
        observationIndex++;
    }

    /**
     * Generates and returns the next observation @f$\mathbf{X}(t_j)@f$
     * of the multivariate stochastic process in a vector created
     * automatically. The processe is sampled *sequentially*, i.e. if the
     * last observation generated was for time @f$t_{j-1}@f$, the next
     * observation returned will be for time @f$t_j@f$.
     */
    public double[] nextObservationVector () {
        double[] obs = new double[c];
        nextObservationVector(obs);
        return obs;
    }

    /**
     * Generates and returns the vector of next observations, at time
     * @f$t_{j+1} = \mathtt{nextTime}@f$, using the previous observation
     * time @f$t_j@f$ defined earlier (either by this method or by
     * <tt>setObservationTimes</tt>), as well as the value of the
     * previous observation @f$X(t_j)@f$. *Warning* : This method will
     * reset the observations time @f$t_{j+1}@f$ for this process to
     * `nextTime`. The user must make sure that the @f$t_{j+1}@f$
     * supplied is @f$\geq t_j@f$.
     */
    public double[] nextObservationVector (double nextTime, double[] obs) {
        if(!covZiSCholDecomp)  // the cholesky factorisation must be done to use the matrix covZCholDecomp
            initCovZCholDecomp();
        t[observationIndex + 1] = nextTime;
        double z;
        for (int i=0; i<c; i++) {
            z = 0.0;
            for (int k=0; k<c; k++)
                z += covZCholDecomp.getQuick(i,k) * gen.nextDouble();
            obs[i] = path[c * observationIndex + i] + mu[i] * (t[observationIndex + 1] - t[observationIndex])
                + Math.sqrt(t[observationIndex + 1] - t[observationIndex]) * z;
            path[c * (observationIndex+1) + i] = obs[i];
        }
        observationIndex++;
        return obs;
    }

    /**
     * Generates an observation (vector) of the process in `dt` time
     * units, assuming that the process has (vector) value @f$x@f$ at the
     * current time. Uses the process parameters specified in the
     * constructor. Note that this method does not affect the sample path
     * of the process stored internally (if any).
     */
    public double[] nextObservationVector (double x[], double dt) {
        double[] obs = new double[c];
        double z;
        if(!covZiSCholDecomp)  // the cholesky factorisation must be done to use the matrix covZCholDecomp
            initCovZCholDecomp();

        for (int i=0; i<c; i++) {
            z = 0.0;
            for (int k=0; k<c; k++)
                z += covZCholDecomp.getQuick(i,k) * gen.nextDouble();
            obs[i] =  x[i] + mu[i] * dt + Math.sqrt(dt) * z;
        }
        observationIndex++;
        return obs;
    }


    public double[] generatePath(){
        double[] u = new double[c*d];
        for (int i=0; i<c*d; i++)
            u[i] = gen.nextDouble();
        return generatePath(u);
    }

/**
 * Same as `generatePath()` but requires a vector of uniform random numbers
 * which are used to generate the path.
 */
public double[] generatePath(double[] uniform01) {
        if(!covZiSCholDecomp){  // the cholesky factorisation must be done to use the matrix covZCholDecomp
            initCovZCholDecomp();
        }
        double z;
        double[] QMCpointsPart = new double[c];
        for (int i=0; i<c; i++)
            path[i] = x0[i];
        for (int j=0; j < d; j++) {
            for (int i=0; i<c; i++) QMCpointsPart[i] = uniform01[j*c + i];
            for (int i=0; i < c; i++) {
                z = 0.0;
                for (int k=0; k<c; k++) z += covZCholDecomp.getQuick(i,k) * QMCpointsPart[k];
                path[c * (j+1) + i] = path[c * j + i] + mu[i] * dt[j] + sqrdt[j] * z;
            }
        }
        observationIndex = d;
        return path;
    }


    public double[] generatePath (RandomStream stream) {
        gen.setStream (stream);
        return generatePath();
    }

/**
 * Sets the dimension @f$c = \mathtt{c}@f$, the initial value
 * @f$\mathbf{X}(t_0) = \mathtt{x0}@f$, the average @f$\mu= \mathtt{mu}@f$,
 * the volatility @f$\sigma= \mathtt{sigma}@f$ and the correlation matrix to
 * `corrZ`. The vectors `x0`, `mu` ans `sigma` must be of size `c` as well as
 * the matrix corrZ must be of size `c x c`. *Warning*: This method will
 * recompute some quantities stored internally, which may be slow if called
 * too frequently.
 */
public void setParams (int c, double x0[], double mu[],
                           double sigma[], double corrZ[][]) {
        this.c     = c;
        this.x0    = x0;
        this.mu    = mu;
        this.sigma = sigma;
        this.corrZ = corrZ;

        if (x0.length < c)
            throw new IllegalArgumentException (
                     "x0 dimension :  "+ x0.length + " is smaller than the process dimension : " + c);
        if (mu.length < c)
            throw new IllegalArgumentException (
                     "mu dimension :  "+ mu.length + " is smaller than the process dimension : " + c);
        if (sigma.length < c)
            throw new IllegalArgumentException (
                     "sigma dimension :  "+ sigma.length + " is smaller than the process dimension : " + c);
        if ((corrZ.length < c) || (corrZ[0].length < c))
            throw new IllegalArgumentException (
                     "corrZ dimensions :  "+ corrZ.length + "x" + corrZ[0].length
                        + " are smaller than the process dimension : " + c);
        covZ = new DenseDoubleMatrix2D(c,c);
        initCovZ();
        covZiSCholDecomp = false;

        if (observationTimesSet) init(); // Otherwise not needed.
    }

    /**
     * Sets the dimension @f$c = \mathtt{c}@f$, the initial value
     * @f$\mathbf{X}(t_0) = \mathtt{x0}@f$, the average @f$\mu=
     * \mathtt{mu}@f$, the volatility @f$\sigma= \mathtt{sigma}@f$.
     * *Warning*: This method will recompute some quantities stored
     * internally, which may be slow if called too frequently.
     */
    public void setParams (double x0[], double mu[], double sigma[]) {
        this.x0    = x0;
        this.mu    = mu;
        this.sigma = sigma;
        covZ = new DenseDoubleMatrix2D(c,c);
        initCovZ();
        covZiSCholDecomp = false;

       if (observationTimesSet) init(); // Otherwise not needed.
    }

    /**
     * Resets the random stream of the normal generator to `stream`.
     */
    public void setStream (RandomStream stream) {
       gen.setStream (stream);
    }

    /**
     * Returns the random stream of the normal generator.
     */
    public RandomStream getStream () { return gen.getStream (); }

    /**
     * Returns the normal random variate generator used. The
     * `RandomStream` used for that generator can be changed via
     * `getGen().setStream(stream)`, for example.
     */
    public NormalGen getGen() {
       return gen;
    }


    // This is called by setObservationTimes to precompute constants
    // in order to speed up the path generation.
    protected void init() {
        super.init();
        dt   = new double[d+1];
        sqrdt = new double[d+1];
        for (int j = 0; j < d; j++) {
            dt[j]    = t[j+1] - t[j];
            sqrdt[j] = Math.sqrt (dt[j]);
        }
     }

    protected void initCovZCholDecomp() {
        covZCholDecomp = new DenseDoubleMatrix2D(c,c);
        covZCholDecomp = DMatrix.CholeskyDecompose(covZ);
        covZiSCholDecomp = true;
     }

    protected void initCovZ() {
    for(int i = 0; i < c; i++)
           for(int j = 0; j < c; j++)
             covZ.setQuick(i, j, corrZ[i][j]*sigma[i]*sigma[j]);
    }

/**
 * Returns the vector `mu`.
 */
public double[] getMu() {
        return mu;
    }

}