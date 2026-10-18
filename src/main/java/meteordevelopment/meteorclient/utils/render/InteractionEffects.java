package meteordevelopment.meteorclient.utils.render;

/** Short, bounded surface and interaction effects; no world queries or particle allocations. */
public final class InteractionEffects {
    public enum Kind {
        Step,
        Water,
        Place,
        Break
    }

    public static final int CAPACITY = 128;

    public static final class Effect {
        public Kind kind;
        public double x, y, z, age, life, size, strength, seed;
        public boolean active;
    }

    private final Effect[] effects = new Effect[CAPACITY];
    private int cursor, sequence;

    public InteractionEffects() {
        for (int i = 0; i < CAPACITY; i++) effects[i] = new Effect();
    }

    public void clear() {
        for (var effect : effects) effect.active = false;
    }

    public Effect[] effects() {
        return effects;
    }

    public void tick(double dt, boolean movement, boolean blocks) {
        for (var e : effects)
            if (e.active) {
                e.age += Math.clamp(dt, 0, .1);
                if (e.age >= e.life
                        || (e.kind == Kind.Step || e.kind == Kind.Water ? !movement : !blocks))
                    e.active = false;
            }
    }

    public void emit(
            Kind kind, double x, double y, double z, double amount, double size, double life) {
        if (amount <= 0 || !Double.isFinite(x + y + z)) return;
        var e = effects[cursor++ % CAPACITY];
        if (cursor >= CAPACITY) cursor = 0;
        e.active = true;
        e.kind = kind;
        e.x = x;
        e.y = y;
        e.z = z;
        e.age = 0;
        e.strength = Math.clamp(amount, 0, 1);
        e.size = Math.clamp(size, .05, 2);
        e.life = Math.clamp(life, .2, 2);
        e.seed = (++sequence * .6180339887498949) % 1;
    }

    public void build(
            CelestialMesh mesh, double cx, double cy, double cz, double partial, int rgb) {
        for (var e : effects) {
            if (!e.active) continue;
            double age = Math.clamp(e.age + partial * .05, 0, e.life), t = age / e.life;
            if (t >= 1) continue;
            double x = e.x - cx, y = e.y - cy, z = cz - e.z;
            double distance = Math.sqrt(x * x + y * y + z * z);
            if (distance > 48) continue;
            double alpha = Math.min(1, t * 8) * Math.pow(1 - t, 1.3) * e.strength;
            if (e.kind == Kind.Water) {
                ring(mesh, x, y, z, .05 + (.35 + e.size * .5) * t, .014, alpha * .75, rgb, true);
                if (t > .18)
                    ring(
                            mesh,
                            x,
                            y + .002,
                            z,
                            (.35 + e.size * .4) * (t - .18),
                            .01,
                            alpha * .35,
                            rgb,
                            true);
            } else if (e.kind == Kind.Step) {
                for (int i = 0; i < 4; i++) {
                    double a = e.seed * 6.28 + i * 2.4, r = t * e.size * .32;
                    dot(
                            mesh,
                            x + Math.cos(a) * r,
                            y + .025 + Math.sin(t * Math.PI) * .15,
                            z + Math.sin(a) * r,
                            .018 * (1 - t),
                            alpha * .7,
                            rgb);
                }
            } else {
                ring(
                        mesh,
                        x,
                        y,
                        z,
                        e.size * (.1 + t * .8),
                        .016 * (1 - t),
                        alpha * .55,
                        rgb,
                        false);
                int sparks = 3 + (int) (e.strength * 7);
                for (int i = 0; i < sparks; i++) {
                    double a = e.seed * 6.28 + i * 2.4, up = Math.sin(i * 3.17 + e.seed) * .6;
                    double r = e.size * t * (e.kind == Kind.Break ? .85 : .55);
                    dot(
                            mesh,
                            x + Math.cos(a) * r,
                            y + up * r,
                            z + Math.sin(a) * r,
                            .025 * (1 - t),
                            alpha,
                            rgb);
                }
            }
        }
    }

    private static void dot(
            CelestialMesh mesh, double x, double y, double z, double size, double alpha, int rgb) {
        double length = Math.max(1e-6, Math.sqrt(x * x + y * y + z * z)), h = Math.hypot(x, z);
        double ux = h < 1e-6 ? 1 : z / h, uz = h < 1e-6 ? 0 : -x / h;
        double vx = y / length * uz, vy = (z * ux - x * uz) / length, vz = -y / length * ux;
        int center = CelestialGeometry.color(rgb, alpha, .7),
                clear = CelestialGeometry.color(rgb, 0, 0);
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4, b = (i + 1) * Math.PI / 4;
            double ax = Math.cos(a) * size,
                    ay = Math.sin(a) * size,
                    bx = Math.cos(b) * size,
                    by = Math.sin(b) * size;
            mesh.tri(
                    x,
                    y,
                    z,
                    x + ux * ax + vx * ay,
                    y + vy * ay,
                    z + uz * ax + vz * ay,
                    x + ux * bx + vx * by,
                    y + vy * by,
                    z + uz * bx + vz * by,
                    center,
                    clear,
                    clear);
        }
    }

    private static void ring(
            CelestialMesh mesh,
            double x,
            double y,
            double z,
            double radius,
            double width,
            double alpha,
            int rgb,
            boolean horizontal) {
        double length = Math.max(1e-6, Math.sqrt(x * x + y * y + z * z)), h = Math.hypot(x, z);
        double ux = horizontal ? 1 : h < 1e-6 ? 1 : z / h,
                uz = horizontal ? 0 : h < 1e-6 ? 0 : -x / h;
        double vx = horizontal ? 0 : y / length * uz,
                vy = horizontal ? 0 : (z * ux - x * uz) / length,
                vz = horizontal ? 1 : -y / length * ux;
        int color = CelestialGeometry.color(rgb, alpha, .5);
        for (int i = 0; i < 32; i++) {
            double a = i * Math.PI / 16, b = (i + 1) * Math.PI / 16;
            double ao = Math.cos(a) * (radius + width),
                    ap = Math.sin(a) * (radius + width),
                    ai = Math.cos(a) * Math.max(0, radius - width),
                    aq = Math.sin(a) * Math.max(0, radius - width);
            double bo = Math.cos(b) * (radius + width),
                    bp = Math.sin(b) * (radius + width),
                    bi = Math.cos(b) * Math.max(0, radius - width),
                    bq = Math.sin(b) * Math.max(0, radius - width);
            mesh.quad(
                    x + ux * ao + vx * ap,
                    y + vy * ap,
                    z + uz * ao + vz * ap,
                    x + ux * ai + vx * aq,
                    y + vy * aq,
                    z + uz * ai + vz * aq,
                    x + ux * bi + vx * bq,
                    y + vy * bq,
                    z + uz * bi + vz * bq,
                    x + ux * bo + vx * bp,
                    y + vy * bp,
                    z + uz * bo + vz * bp,
                    color,
                    color,
                    color,
                    color);
        }
    }
}
