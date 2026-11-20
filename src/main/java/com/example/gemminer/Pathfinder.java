package com.example.gemminer;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;

import java.util.*;

/** Simple A* walker: flat, +1 up, up to 3 down, optional bridging over gaps. Cardinal moves only. */
public class Pathfinder {

    public enum Move { FLAT, UP, DOWN, BRIDGE }

    public static class Node {
        public final BlockPos pos;
        public final Move move;
        Node(BlockPos pos, Move move) { this.pos = pos; this.move = move; }
    }

    public interface Goal {
        boolean reached(BlockPos p);
        double heuristic(BlockPos p);
    }

    private static class N implements Comparable<N> {
        final BlockPos pos; final N parent; final Move move; final double g, f;
        N(BlockPos pos, N parent, Move move, double g, double f) {
            this.pos = pos; this.parent = parent; this.move = move; this.g = g; this.f = f;
        }
        @Override public int compareTo(N o) { return Double.compare(f, o.f); }
    }

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    public static boolean passable(World w, BlockPos p) {
        if (p.getY() < 0 || p.getY() > 255) return false;
        IBlockState s = w.getBlockState(p);
        Block b = s.getBlock();
        if (b.getMaterial().isLiquid()) return false;
        if (b == Blocks.fire || b == Blocks.web || b == Blocks.cactus) return false;
        return b.getCollisionBoundingBox(w, p, s) == null;
    }

    public static boolean solid(World w, BlockPos p) {
        IBlockState s = w.getBlockState(p);
        Block b = s.getBlock();
        if (b.getMaterial().isLiquid() || b == Blocks.cactus) return false;
        return b.getMaterial().isSolid() && b.getCollisionBoundingBox(w, p, s) != null;
    }

    public static boolean canStand(World w, BlockPos p) {
        return passable(w, p) && passable(w, p.up()) && solid(w, p.down());
    }

    public static List<Node> find(World w, BlockPos start, Goal goal, boolean allowBridge, int maxNodes) {
        Map<BlockPos, N> best = new HashMap<BlockPos, N>();
        Set<BlockPos> closed = new HashSet<BlockPos>();
        PriorityQueue<N> open = new PriorityQueue<N>();
        N s = new N(start, null, Move.FLAT, 0, goal.heuristic(start));
        open.add(s);
        best.put(start, s);
        int expanded = 0;

        while (!open.isEmpty() && expanded < maxNodes) {
            N cur = open.poll();
            if (closed.contains(cur.pos)) continue;
            if (goal.reached(cur.pos)) return build(cur);
            closed.add(cur.pos);
            expanded++;

            for (int[] d : DIRS) {
                BlockPos f = cur.pos.add(d[0], 0, d[1]);

                if (canStand(w, f)) {
                    relax(cur, f, Move.FLAT, 1.0, goal, best, open);
                    continue;
                }
                // up 1 block
                BlockPos u = f.up();
                if (canStand(w, u) && passable(w, cur.pos.up(2))) {
                    relax(cur, u, Move.UP, 2.0, goal, best, open);
                    continue;
                }
                // walk off edge and fall (max 3)
                if (passable(w, f) && passable(w, f.up())) {
                    boolean found = false;
                    for (int k = 1; k <= 3; k++) {
                        BlockPos feet = f.down(k);
                        if (!passable(w, feet)) break;
                        if (solid(w, feet.down())) {
                            relax(cur, feet, Move.DOWN, 1.0 + k * 0.5, goal, best, open);
                            found = true;
                            break;
                        }
                    }
                    // bridge over the gap
                    if (!found && allowBridge && !w.getBlockState(f.down()).getBlock().getMaterial().isLiquid()) {
                        relax(cur, f, Move.BRIDGE, 6.0, goal, best, open);
                    }
                }
            }
        }
        return null;
    }

    private static void relax(N cur, BlockPos np, Move mv, double cost, Goal goal,
                              Map<BlockPos, N> best, PriorityQueue<N> open) {
        double ng = cur.g + cost;
        N ex = best.get(np);
        if (ex != null && ng >= ex.g) return;
        N nn = new N(np, cur, mv, ng, ng + goal.heuristic(np));
        best.put(np, nn);
        open.add(nn);
    }

    private static List<Node> build(N end) {
        LinkedList<Node> out = new LinkedList<Node>();
        for (N n = end; n != null && n.parent != null; n = n.parent)
            out.addFirst(new Node(n.pos, n.move));
        return out;
    }
}
