package ca.pwalker.ultratone;

import java.util.ArrayList;
import java.util.List;

/** Maps first-frame coordinates into each later frame. All coordinates are in upright pixels. */
final class FrameAlignment {
    final int width, height;
    final byte[] gray;
    final FrameAlignment coarse;

    FrameAlignment(int[] pixels, int width, int height) {
        this.width = width;
        this.height = height;
        gray = new byte[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            gray[i] = (byte)((54 * ((p >> 16) & 255) + 183 * ((p >> 8) & 255) + 19 * (p & 255)) >> 8);
        }
        coarse = smaller();
    }

    private FrameAlignment(FrameAlignment parent) {
        width = parent.width / 2;
        height = parent.height / 2;
        gray = new byte[width * height];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int i = y * 2 * parent.width + x * 2;
            gray[y * width + x] = (byte)((parent.at(i) + parent.at(i+1)
                    + parent.at(i+parent.width) + parent.at(i+parent.width+1)) / 4);
        }
        coarse = smaller();
    }

    private FrameAlignment smaller() {
        return Math.max(width, height) > 360 && Math.min(width, height) >= 96 ? new FrameAlignment(this) : null;
    }

    private int at(int index) { return gray[index] & 255; }

    // Integer patch matching at each pyramid level. Use mean-adjusted error for exposure shifts.
    private int error(FrameAlignment next, int x, int y, int dx, int dy) {
        int sum = 0, square = 0;
        for (int row = -3; row <= 3; row++) {
            int i = (y + row) * width + x - 3;
            int j = i + dy * width + dx;
            for (int k = 0; k < 7; k++) {
                int delta = at(i+k) - next.at(j+k);
                sum += delta;
                square += delta * delta;
            }
        }
        return square - sum * sum / 49;
    }

    private static final class Match {
        final int x, y;
        int dx, dy;
        Match(int x, int y) { this.x=x; this.y=y; }
    }

    private List<Match> matches(FrameAlignment next) {
        List<Match> seeds = new ArrayList<>();
        if (coarse != null) {
            for (Match m : coarse.matches(next.coarse)) {
                Match fine = new Match(m.x*2, m.y*2);
                fine.dx = m.dx*2;
                fine.dy = m.dy*2;
                seeds.add(fine);
            }
        } else {
            // Pick one two-dimensional feature per tile so a single moving object has limited influence.
            for (int gy=0; gy<5; gy++) for (int gx=0; gx<6; gx++) {
                Match best = null;
                double strength = 80;
                for (int y=Math.max(5,gy*height/5); y<Math.min(height-5,(gy+1)*height/5); y+=3)
                    for (int x=Math.max(5,gx*width/6); x<Math.min(width-5,(gx+1)*width/6); x+=3) {
                        int xx=0, yy=0, xy=0;
                        for (int v=-2; v<=2; v+=2) for (int u=-2; u<=2; u+=2) {
                            int i=(y+v)*width+x+u;
                            int a=at(i+1)-at(i-1), b=at(i+width)-at(i-width);
                            xx+=a*a; yy+=b*b; xy+=a*b;
                        }
                        double score=((double)xx*yy-(double)xy*xy)/(1.0+xx+yy);
                        if (score>strength) { best=new Match(x,y); strength=score; }
                    }
                if (best!=null) seeds.add(best);
            }
        }
        List<Match> found = new ArrayList<>();
        int radius = coarse == null ? 12 : 3;
        for (Match m : seeds) {
            int best=Integer.MAX_VALUE, bx=0, by=0;
            int second=Integer.MAX_VALUE;
            for (int dy=m.dy-radius; dy<=m.dy+radius; dy++) for (int dx=m.dx-radius; dx<=m.dx+radius; dx++) {
                if (m.x+dx<3 || m.y+dy<3 || m.x+dx>=width-3 || m.y+dy>=height-3) continue;
                int cost=error(next,m.x,m.y,dx,dy);
                if (cost<best) { second=best; best=cost; bx=dx; by=dy; }
                else if (cost<second) second=cost;
            }
            if (best<49*65*65 && (coarse!=null || best<second*0.93)) {
                m.dx=bx; m.dy=by; found.add(m);
            }
        }
        return found;
    }

    // Fit a rigid transform to spatially separated pairs, then refine on the agreeing background.
    double[] transformTo(FrameAlignment next) {
        List<Match> points=matches(next);
        int bestCount=0;
        double bestError=Double.MAX_VALUE;
        double[] best=null;
        double tolerance=Math.max(1.75,Math.max(width,height)*0.001);
        for (int i=0;i<points.size();i++) for (int j=i+1;j<points.size();j++) {
            Match a=points.get(i), b=points.get(j);
            double vx=b.x-a.x, vy=b.y-a.y;
            if (Math.hypot(vx,vy)<Math.max(width,height)*0.25) continue;
            double ux=vx+b.dx-a.dx, uy=vy+b.dy-a.dy;
            double angle=Math.atan2(vx*uy-vy*ux,vx*ux+vy*uy);
            double c=Math.cos(angle), s=Math.sin(angle);
            double tx=a.x+a.dx-c*a.x+s*a.y, ty=a.y+a.dy-s*a.x-c*a.y;
            int count=0; double error=0;
            for (Match p:points) {
                double r=residual(p,c,s,tx,ty);
                if (r<=tolerance) { count++; error+=r*r; }
            }
            if (count>bestCount || (count==bestCount && error<bestError)) {
                bestCount=count; bestError=error; best=new double[]{c,s,tx,ty};
            }
        }
        if (best==null || bestCount<4 || bestCount*2<=points.size()) return null;
        double x=0,y=0,u=0,v=0;
        List<Match> inliers=new ArrayList<>();
        for (Match p:points) if (residual(p,best[0],best[1],best[2],best[3])<=tolerance) {
            inliers.add(p); x+=p.x; y+=p.y; u+=p.x+p.dx; v+=p.y+p.dy;
        }
        x/=bestCount; y/=bestCount; u/=bestCount; v/=bestCount;
        double dot=0,cross=0;
        for (Match p:inliers) {
            double a=p.x-x,b=p.y-y,d=p.x+p.dx-u,e=p.y+p.dy-v;
            dot+=a*d+b*e; cross+=a*e-b*d;
        }
        double angle=Math.atan2(cross,dot), c=Math.cos(angle), s=Math.sin(angle);
        return new double[]{c,s,u-c*x+s*y,v-s*x-c*y};
    }

    private static double residual(Match p,double c,double s,double tx,double ty) {
        return Math.hypot(c*p.x-s*p.y+tx-p.x-p.dx,s*p.x+c*p.y+ty-p.y-p.dy);
    }
}
