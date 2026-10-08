package com.kncatl.ohmyworld.expr;

/**
 * 噪声与哈希原语（1.2.5 起的"冻结契约"实现，1.3.0 从 {@code ExprEvaluator} 迁出并扩充）。
 *
 * <p><b>算法一经发布不得更改</b>——同一公式与种子在任何版本必须生成相同的世界。
 * 需要改进时新增函数名（或带 {@code @v2} 语义的新名），不要修改这里已有函数的输出。
 *
 * <p>全部函数是纯函数（世界种子除外，由 {@link #setWorldSeed(long)} 注入，运行期只读）；
 * 与区块生成顺序、线程、缓存与否无关。
 */
final class Noise {

    private Noise() {}

    /** 当前世界的种子；由 {@code ExprEvaluator.setWorldSeed} 写入。 */
    static volatile long worldSeed;

    static void setWorldSeed(long seed) { worldSeed = seed; }

    // ---------------------------------------------------------- 哈希

    /** SplitMix64 的混淆函数（seedhash / 晶格哈希的公共基础，冻结）。 */
    static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** [0,1) 均匀值（取高 53 位）。 */
    static double unit(long h) {
        return (h >>> 11) * 0x1.0p-53;
    }

    /** 2D 噪声晶格哈希：混合世界种子、盐与晶格坐标（冻结契约）。 */
    static long latticeHash(double salt, int x, int z) {
        long h = mix64(worldSeed + Double.doubleToRawLongBits(salt) * 0x9E3779B97F4A7C15L);
        h = mix64(h + x * 0x9E3779B97F4A7C15L);
        h = mix64(h + z * 0xC2B2AE3D27D4EB4FL);
        return h;
    }

    /** 3D 噪声晶格哈希（冻结契约）。 */
    static long latticeHash(double salt, int x, int y, int z) {
        long h = mix64(worldSeed + Double.doubleToRawLongBits(salt) * 0x9E3779B97F4A7C15L);
        h = mix64(h + x * 0x9E3779B97F4A7C15L);
        h = mix64(h + y * 0x165667B19E3779F9L);
        h = mix64(h + z * 0xC2B2AE3D27D4EB4FL);
        return h;
    }

    // ---------------------------------------------------------- 梯度噪声

    private static final double SQRT_HALF = 0.7071067811865476;

    /** 2D 单位梯度（8 向）。 */
    private static final double[] GRAD2_X = {1, -1, 0, 0, SQRT_HALF, -SQRT_HALF, SQRT_HALF, -SQRT_HALF};
    private static final double[] GRAD2_Z = {0, 0, 1, -1, SQRT_HALF, SQRT_HALF, -SQRT_HALF, -SQRT_HALF};

    /** 3D 单位梯度（经典 12 棱向）。 */
    private static final double[] GRAD3_X =
            {SQRT_HALF, -SQRT_HALF, SQRT_HALF, -SQRT_HALF, SQRT_HALF, -SQRT_HALF, SQRT_HALF, -SQRT_HALF, 0, 0, 0, 0};
    private static final double[] GRAD3_Y =
            {SQRT_HALF, SQRT_HALF, -SQRT_HALF, -SQRT_HALF, 0, 0, 0, 0, SQRT_HALF, -SQRT_HALF, SQRT_HALF, -SQRT_HALF};
    private static final double[] GRAD3_Z =
            {0, 0, 0, 0, SQRT_HALF, SQRT_HALF, -SQRT_HALF, -SQRT_HALF, SQRT_HALF, SQRT_HALF, -SQRT_HALF, -SQRT_HALF};

    /** 五次平滑（Perlin 的 fade）。 */
    static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    /** fade 的导数：30 t² (t-1)²。 */
    private static double fadeDeriv(double t) {
        double u = t * (t - 1);
        return 30 * u * u;
    }

    private static int grad2Index(long h) {
        return (int) ((h & 0x7FFFFFFFL) % 8);
    }

    private static double grad2(long h, double dx, double dz) {
        int gi = grad2Index(h);
        return GRAD2_X[gi] * dx + GRAD2_Z[gi] * dz;
    }

    private static double grad3(long h, double dx, double dy, double dz) {
        int gi = (int) ((h & 0x7FFFFFFFL) % 12);
        return GRAD3_X[gi] * dx + GRAD3_Y[gi] * dy + GRAD3_Z[gi] * dz;
    }

    /**
     * {@code noise2(x, z, scale, salt)}：2D 平滑梯度噪声，返回 [-1, 1]。
     * {@code scale} 是特征尺度（方块）——值越大、特征越大；{@code salt} 换一套图案。
     */
    static double noise2(double x, double z, double scale, double salt) {
        double s = scale > 0 ? scale : 1;
        double fx = x / s;
        double fz = z / s;
        int x0 = (int) Math.floor(fx);
        int z0 = (int) Math.floor(fz);
        double tx = fx - x0;
        double tz = fz - z0;
        double u = fade(tx);
        double v = fade(tz);
        double n00 = grad2(latticeHash(salt, x0, z0), tx, tz);
        double n10 = grad2(latticeHash(salt, x0 + 1, z0), tx - 1, tz);
        double n01 = grad2(latticeHash(salt, x0, z0 + 1), tx, tz - 1);
        double n11 = grad2(latticeHash(salt, x0 + 1, z0 + 1), tx - 1, tz - 1);
        double a = n00 + u * (n10 - n00);
        double b = n01 + u * (n11 - n01);
        return clamp((a + v * (b - a)) * 1.4142135623730951, -1, 1);
    }

    /**
     * {@code noise2} 的解析梯度：返回 {值, ∂值/∂x, ∂值/∂z}。
     *
     * <p>与 {@code noise2} 共用同一晶格与插值；值被 ±1 截断处梯度按 0 处理
     * （返回的是"实际输出值"的梯度）。算法冻结。
     */
    static double[] noise2Grad(double x, double z, double scale, double salt) {
        double s = scale > 0 ? scale : 1;
        double fx = x / s;
        double fz = z / s;
        int x0 = (int) Math.floor(fx);
        int z0 = (int) Math.floor(fz);
        double tx = fx - x0;
        double tz = fz - z0;
        double u = fade(tx);
        double v = fade(tz);
        double du = fadeDeriv(tx);
        double dv = fadeDeriv(tz);

        long h00 = latticeHash(salt, x0, z0);
        long h10 = latticeHash(salt, x0 + 1, z0);
        long h01 = latticeHash(salt, x0, z0 + 1);
        long h11 = latticeHash(salt, x0 + 1, z0 + 1);
        int g00 = grad2Index(h00), g10 = grad2Index(h10), g01 = grad2Index(h01), g11 = grad2Index(h11);

        // 角点贡献与各自的常数梯度（扰动向量固定，点坐标线性）
        double n00 = GRAD2_X[g00] * tx + GRAD2_Z[g00] * tz;
        double n10 = GRAD2_X[g10] * (tx - 1) + GRAD2_Z[g10] * tz;
        double n01 = GRAD2_X[g01] * tx + GRAD2_Z[g01] * (tz - 1);
        double n11 = GRAD2_X[g11] * (tx - 1) + GRAD2_Z[g11] * (tz - 1);

        double a = n00 + u * (n10 - n00);
        double b = n01 + u * (n11 - n01);
        double raw = a + v * (b - a);

        // ∂raw/∂tx 与 ∂raw/∂tz
        double da = GRAD2_X[g00] + du * (n10 - n00) + u * (GRAD2_X[g10] - GRAD2_X[g00]);
        double db = GRAD2_X[g01] + du * (n11 - n01) + u * (GRAD2_X[g11] - GRAD2_X[g01]);
        double drawX = da + v * (db - da);
        double dadtz = GRAD2_Z[g00] + u * (GRAD2_Z[g10] - GRAD2_Z[g00]);
        double dbdtz = GRAD2_Z[g01] + u * (GRAD2_Z[g11] - GRAD2_Z[g01]);
        double drawZ = dadtz + dv * (b - a) + v * (dbdtz - dadtz);

        double scaled = raw * 1.4142135623730951;
        if (scaled > 1 || scaled < -1) {
            return new double[]{clamp(scaled, -1, 1), 0, 0};
        }
        double inv = 1.4142135623730951 / s;
        return new double[]{scaled, drawX * inv, drawZ * inv};
    }

    /** {@code noise3(x, y, z, scale, salt)}：3D 平滑梯度噪声，返回 [-1, 1]。 */
    static double noise3(double x, double y, double z, double scale, double salt) {
        double s = scale > 0 ? scale : 1;
        double fx = x / s;
        double fy = y / s;
        double fz = z / s;
        int x0 = (int) Math.floor(fx);
        int y0 = (int) Math.floor(fy);
        int z0 = (int) Math.floor(fz);
        double tx = fx - x0;
        double ty = fy - y0;
        double tz = fz - z0;
        double u = fade(tx);
        double v = fade(ty);
        double w = fade(tz);
        double n000 = grad3(latticeHash(salt, x0, y0, z0), tx, ty, tz);
        double n100 = grad3(latticeHash(salt, x0 + 1, y0, z0), tx - 1, ty, tz);
        double n010 = grad3(latticeHash(salt, x0, y0 + 1, z0), tx, ty - 1, tz);
        double n110 = grad3(latticeHash(salt, x0 + 1, y0 + 1, z0), tx - 1, ty - 1, tz);
        double n001 = grad3(latticeHash(salt, x0, y0, z0 + 1), tx, ty, tz - 1);
        double n101 = grad3(latticeHash(salt, x0 + 1, y0, z0 + 1), tx - 1, ty, tz - 1);
        double n011 = grad3(latticeHash(salt, x0, y0 + 1, z0 + 1), tx, ty - 1, tz - 1);
        double n111 = grad3(latticeHash(salt, x0 + 1, y0 + 1, z0 + 1), tx - 1, ty - 1, tz - 1);
        double a = n000 + u * (n100 - n000);
        double b = n010 + u * (n110 - n010);
        double c = n001 + u * (n101 - n001);
        double d = n011 + u * (n111 - n011);
        double e = a + v * (b - a);
        double f = c + v * (d - c);
        return clamp((e + w * (f - e)) * 1.1547005383792515, -1, 1);
    }

    // ---------------------------------------------------------- 多倍频 / 变体

    /**
     * {@code fbm2(x, z, scale, octaves, salt)}：多倍频叠加（gain 0.5、每层特征尺度减半），
     * 返回 [-1, 1]；octaves 夹在 1..8。冻结契约。
     */
    static double fbm2(double x, double z, double scale, double octaves, double salt) {
        int count = (int) clamp(octaves, 1, 8);
        double s = scale > 0 ? scale : 1;
        double sum = 0;
        double norm = 0;
        double amp = 1;
        for (int i = 0; i < count; i++) {
            sum += amp * noise2(x, z, s, salt + i);
            norm += amp;
            amp *= 0.5;
            s *= 0.5;
        }
        return sum / norm;
    }

    /** {@code fbm3(x, y, z, scale, octaves, salt)}：3D 多倍频叠加，返回 [-1, 1]；冻结契约。 */
    static double fbm3(double x, double y, double z, double scale, double octaves, double salt) {
        int count = (int) clamp(octaves, 1, 8);
        double s = scale > 0 ? scale : 1;
        double sum = 0;
        double norm = 0;
        double amp = 1;
        for (int i = 0; i < count; i++) {
            sum += amp * noise3(x, y, z, s, salt + i);
            norm += amp;
            amp *= 0.5;
            s *= 0.5;
        }
        return sum / norm;
    }

    /**
     * {@code fbm2(x, z, scale, octaves, salt, lacunarity, gain)}：可调倍频间距与衰减的
     * 重载；每倍频加一个由 (salt, 序号) 哈希确定的坐标偏移（幅度 0.5·s_i），
     * 打散多层的晶格对齐（去轴向网格感）。*
     * <p>lacunarity 夹在 [1.01, 4]，gain 夹在 [0, 1]；返回 [-1, 1]。算法冻结。
     */
    static double fbm2(double x, double z, double scale, double octaves, double salt,
                       double lacunarity, double gain) {
        int count = (int) clamp(octaves, 1, 8);
        double lac = clamp(lacunarity, 1.01, 4);
        double g = clamp(gain, 0, 1);
        double s = scale > 0 ? scale : 1;
        double sum = 0;
        double norm = 0;
        double amp = 1;
        for (int i = 0; i < count; i++) {
            double ox = (octaveUnit(salt, i, 0) - 0.5) * s;
            double oz = (octaveUnit(salt, i, 1) - 0.5) * s;
            sum += amp * noise2(x + ox, z + oz, s, salt + i);
            norm += amp;
            amp *= g;
            s /= lac;
        }
        return norm == 0 ? 0 : sum / norm;
    }

    /** 每倍频偏移用的 [0,1) 哈希（只依赖盐与序号，跨世界一致；冻结）。 */
    private static double octaveUnit(double salt, int octave, int axis) {
        long h = Double.doubleToRawLongBits(salt);
        h = mix64(h + 0x9E3779B97F4A7C15L * (octave + 1));
        h = mix64(h + 0xD1B54A32D192ED03L * (axis + 1));
        return unit(h);
    }

    /**
     * {@code fbma2(x, z, scale, salt, a0, a1, ...)}：任意振幅列表的多倍频叠加
     * （仿原版 NormalNoise 的 amplitudes 思路）：octave i 使用特征尺度
     * {@code scale / 2^i}、盐 {@code salt + i}、振幅 {@code a_i}；结果除以
     * {@code Σ|a_i|} 归一化，返回 [-1, 1]。振幅个数 1..8。算法冻结。
     */
    static double fbma2(double x, double z, double scale, double salt, double[] amplitudes) {
        double s = scale > 0 ? scale : 1;
        double sum = 0;
        double norm = 0;
        for (int i = 0; i < amplitudes.length; i++) {
            sum += amplitudes[i] * noise2(x, z, s, salt + i);
            norm += Math.abs(amplitudes[i]);
            s *= 0.5;
        }
        return norm == 0 ? 0 : sum / norm;
    }

    /**
     * {@code ridged2(x, z, scale, octaves, salt, sharp)}：山脊多重分形——
     * 每层取 {@code (1 - |noise|)^sharp}，gain 0.5 叠加后归一，返回 [0, 1]。
     * octaves 1..8、sharp 夹在 [1, 8]。算法冻结。
     */
    static double ridged2(double x, double z, double scale, double octaves, double salt, double sharp) {
        int count = (int) clamp(octaves, 1, 8);
        double sh = clamp(sharp, 1, 8);
        double s = scale > 0 ? scale : 1;
        double sum = 0;
        double norm = 0;
        double amp = 1;
        for (int i = 0; i < count; i++) {
            double n = 1 - Math.abs(noise2(x, z, s, salt + i));
            sum += amp * Math.pow(n, sh);
            norm += amp;
            amp *= 0.5;
            s *= 0.5;
        }
        return sum / norm;
    }

    /**
     * {@code billow2(x, z, scale, octaves, salt, sharp)}：圆丘多重分形——
     * 每层取 {@code |noise|^sharp}，gain 0.5 叠加后归一，返回 [0, 1]。
     * 参数范围同 {@link #ridged2}。算法冻结。
     */
    static double billow2(double x, double z, double scale, double octaves, double salt, double sharp) {
        int count = (int) clamp(octaves, 1, 8);
        double sh = clamp(sharp, 1, 8);
        double s = scale > 0 ? scale : 1;
        double sum = 0;
        double norm = 0;
        double amp = 1;
        for (int i = 0; i < count; i++) {
            double n = Math.abs(noise2(x, z, s, salt + i));
            sum += amp * Math.pow(n, sh);
            norm += amp;
            amp *= 0.5;
            s *= 0.5;
        }
        return sum / norm;
    }

    /**
     * {@code fbm2e(x, z, scale, octaves, salt, k)}：梯度衰减 fbm——每叠一层后
     * 用累计梯度决定下一层振幅的阻尼 {@code 1/(1 + k·|∇Σ|·s_i)}（s_i 为该层特征尺度），
     * 坡陡处细节被抑制，呈侵蚀感。k 夹在 [0, 8]；k=0 时与
     * {@code fbm2(x, z, scale, octaves, salt)} 完全一致。算法冻结。
     */
    static double fbm2e(double x, double z, double scale, double octaves, double salt, double k) {
        int count = (int) clamp(octaves, 1, 8);
        double damp = clamp(k, 0, 8);
        double s = scale > 0 ? scale : 1;
        double sum = 0;
        double norm = 0;
        double gx = 0;
        double gz = 0;
        double amp = 1;
        for (int i = 0; i < count; i++) {
            double[] ng = noise2Grad(x, z, s, salt + i);
            sum += amp * ng[0];
            norm += amp;
            gx += amp * ng[1];
            gz += amp * ng[2];
            double slope = Math.sqrt(gx * gx + gz * gz) * s;
            amp *= 0.5 / (1 + damp * slope);
            s *= 0.5;
        }
        return sum / norm;
    }

    // ---------------------------------------------------------- 细胞噪声

    /**
     * {@code worley2(x, z, scale, salt)}：细胞噪声的最近特征点距离，
     * 以 {@code scale} 为单位并截断到 [0, 1]（0 = 恰在特征点上）。冻结契约。
     */
    static double worley2(double x, double z, double scale, double salt) {
        double s = scale > 0 ? scale : 1;
        double fx = x / s;
        double fz = z / s;
        int cx = (int) Math.floor(fx);
        int cz = (int) Math.floor(fz);
        double best = 2;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int gx = cx + dx;
                int gz = cz + dz;
                long h = latticeHash(salt, gx, gz);
                double px = gx + unit(h);
                double pz = gz + unit(mix64(h + 0x9E3779B97F4A7C15L));
                double ddx = px - fx;
                double ddz = pz - fz;
                double d = Math.sqrt(ddx * ddx + ddz * ddz);
                if (d < best) best = d;
            }
        }
        return clamp(best, 0, 1);
    }

    /**
     * {@code worley2f2(x, z, 尺度, 盐)}：2D 细胞噪声的**第二近**特征点距离（F2），
     * 与 {@code worley2} 同量纲（细胞单位、[0,1] 截断）。算法冻结。
     */
    static double worley2f2(double x, double z, double scale, double salt) {
        double s = scale > 0 ? scale : 1;
        double fx = x / s;
        double fz = z / s;
        int cx = (int) Math.floor(fx);
        int cz = (int) Math.floor(fz);
        double best1 = 2;
        double best2 = 2;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int gx = cx + dx;
                int gz = cz + dz;
                long h = latticeHash(salt, gx, gz);
                double px = gx + unit(h);
                double pz = gz + unit(mix64(h + 0x9E3779B97F4A7C15L));
                double ddx = px - fx;
                double ddz = pz - fz;
                double d = Math.sqrt(ddx * ddx + ddz * ddz);
                if (d < best1) {
                    best2 = best1;
                    best1 = d;
                } else if (d < best2) {
                    best2 = d;
                }
            }
        }
        return clamp(best2, 0, 1);
    }

    /**
     * {@code worley2edge(x, z, 尺度, 盐)}：F2 - F1（细胞边缘线）——越接近 0 越靠近
     * 两个特征点的等分边界，适合"裂纹 / 领地边界 / 冰裂缝"；细胞内部远离边界时更大。
     * 算法冻结：发布后不得更改（对未截断的 F1/F2 做差再截断）。
     */
    static double worley2edge(double x, double z, double scale, double salt) {
        double s = scale > 0 ? scale : 1;
        double fx = x / s;
        double fz = z / s;
        int cx = (int) Math.floor(fx);
        int cz = (int) Math.floor(fz);
        double best1 = 2;
        double best2 = 2;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int gx = cx + dx;
                int gz = cz + dz;
                long h = latticeHash(salt, gx, gz);
                double px = gx + unit(h);
                double pz = gz + unit(mix64(h + 0x9E3779B97F4A7C15L));
                double ddx = px - fx;
                double ddz = pz - fz;
                double d = Math.sqrt(ddx * ddx + ddz * ddz);
                if (d < best1) {
                    best2 = best1;
                    best1 = d;
                } else if (d < best2) {
                    best2 = d;
                }
            }
        }
        return clamp(best2 - best1, 0, 1);
    }

    /**
     * {@code worley2c(x, z, scale, salt)}：返回 {F1, F2, cellRand, px, pz}——
     * F1/F2 同 {@link #worley2f2}；cellRand 是最近细胞哈希决定的 [0,1) 随机值；
     * px/pz 是最近特征点的**方块坐标**。并列与扫描顺序与 worley2f2 一致。算法冻结。
     */
    static double[] worley2c(double x, double z, double scale, double salt) {
        double s = scale > 0 ? scale : 1;
        double fx = x / s;
        double fz = z / s;
        int cx = (int) Math.floor(fx);
        int cz = (int) Math.floor(fz);
        double best1 = 2;
        double best2 = 2;
        double cellRand = 0;
        double pointX = 0;
        double pointZ = 0;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int gx = cx + dx;
                int gz = cz + dz;
                long h = latticeHash(salt, gx, gz);
                double lx = unit(h);
                double lz = unit(mix64(h + 0x9E3779B97F4A7C15L));
                double px = gx + lx;
                double pz = gz + lz;
                double ddx = px - fx;
                double ddz = pz - fz;
                double d = Math.sqrt(ddx * ddx + ddz * ddz);
                if (d < best1) {
                    best2 = best1;
                    best1 = d;
                    cellRand = unit(mix64(h + 0xD1B54A32D192ED03L));
                    pointX = px * s;
                    pointZ = pz * s;
                } else if (d < best2) {
                    best2 = d;
                }
            }
        }
        return new double[]{clamp(best1, 0, 1), clamp(best2, 0, 1), cellRand, pointX, pointZ};
    }

    /** {@code worley3(x, y, z, scale, salt)}：3D 细胞噪声的最近特征点距离（截断到 [0, 1]）。 */
    static double worley3(double x, double y, double z, double scale, double salt) {
        double s = scale > 0 ? scale : 1;
        double fx = x / s;
        double fy = y / s;
        double fz = z / s;
        int cx = (int) Math.floor(fx);
        int cy = (int) Math.floor(fy);
        int cz = (int) Math.floor(fz);
        double best = 2;
        for (int oy = -1; oy <= 1; oy++) {
            for (int oz = -1; oz <= 1; oz++) {
                for (int ox = -1; ox <= 1; ox++) {
                    int gx = cx + ox;
                    int gy = cy + oy;
                    int gz = cz + oz;
                    long h = latticeHash(salt, gx, gy, gz);
                    double px = gx + unit(h);
                    double py = gy + unit(mix64(h + 0x9E3779B97F4A7C15L));
                    double pz = gz + unit(mix64(h + 0xC2B2AE3D27D4EB4FL));
                    double ddx = px - fx;
                    double ddy = py - fy;
                    double ddz = pz - fz;
                    double d = Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz);
                    if (d < best) best = d;
                }
            }
        }
        return clamp(best, 0, 1);
    }

    // ---------------------------------------------------------- 域扭曲（1.3.0）

    /**
     * {@code warp2(x, z, scale, amp, salt)}：2D 域扭曲位移
     * {(dx, dz)}：dx = amp·noise2(x, z, scale, salt)，dz = amp·noise2(x, z, scale, salt+0.5)。
     * 典型用法 {@code let (u, v) = warp2(x, z, 90, 30, 42); ... x + u ...}。算法冻结。
     */
    static double[] warp2(double x, double z, double scale, double amp, double salt) {
        return new double[]{
                amp * noise2(x, z, scale, salt),
                amp * noise2(x, z, scale, salt + 0.5)};
    }

    /**
     * {@code warp3(x, y, z, scale, amp, salt)}：3D 域扭曲位移 {(dx, dy, dz)}，
     * 三个分量分别用盐 salt / salt+0.5 / salt+0.25 的 noise3。算法冻结。
     */
    static double[] warp3(double x, double y, double z, double scale, double amp, double salt) {
        return new double[]{
                amp * noise3(x, y, z, scale, salt),
                amp * noise3(x, y, z, scale, salt + 0.5),
                amp * noise3(x, y, z, scale, salt + 0.25)};
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
