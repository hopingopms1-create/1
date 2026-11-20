package com.example.gemminer;

import net.minecraft.block.Block;
import net.minecraft.block.BlockStainedGlass;
import net.minecraft.block.BlockStainedGlassPane;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.init.Blocks;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

import java.util.*;

@Mod(modid = "gemminer", name = "GemMiner", version = "2.0",
        acceptedMinecraftVersions = "[1.8.9]", clientSideOnly = true)
public class GemMiner {

    private enum State { IDLE, SELECT, AIM, MINE, PATH }
    private enum Purpose { GEM, WAYPOINT }
    private static final String CAT = "GemMiner";

    // ---------- Keybinds (Options > Controls > GemMiner) ----------
    private final KeyBinding kToggle = new KeyBinding("Mining On/Off",        Keyboard.KEY_G, CAT);
    private final KeyBinding kAddWp  = new KeyBinding("Add Waypoint",         Keyboard.KEY_H, CAT);
    private final KeyBinding kClearWp= new KeyBinding("Clear Waypoints",      Keyboard.KEY_J, CAT);
    private final KeyBinding kUndoWp = new KeyBinding("Remove Last Waypoint", Keyboard.KEY_L, CAT);
    private final KeyBinding kAotv   = new KeyBinding("Toggle AOTV Mode",     Keyboard.KEY_K, CAT);
    private final KeyBinding kBridge = new KeyBinding("Toggle Bridging",      Keyboard.KEY_B, CAT);

    // ---------- Settings ----------
    private final double tpRange = 8.0;
    private final double mineReach = 4.5;       // eye -> block centre
    private final double arriveReach = 3.8;     // path goal: eye distance to gemstone
    private boolean dig = true;               // gem ke samne ka block todna (/hsm dig)
    private Block targetBlock = null;
    private boolean debug = false;             // /hsm debug
    private long lastEmptyDbg = 0;
    private int farRadius = 12;                 // /hsm range <4-32>
    private float rotMult = 2.5f;               // /hsm speed <0.5-6>
    private final long mineTimeoutMs = 6000;
    private final long noBlockWaitMs = 2000;

    // ---------- Data ----------
    private State state = State.IDLE;
    private Purpose purpose = Purpose.GEM;
    private final Set<IBlockState> registered = new HashSet<IBlockState>();
    private final Map<BlockPos, Long> skipUntil = new HashMap<BlockPos, Long>();
    private final List<BlockPos> waypoints = new ArrayList<BlockPos>();
    private int wpIndex = 0;
    private BlockPos target = null;
    private BlockPos gemTarget = null;
    private Vec3 lastPos = null;
    private boolean useAotv = false;
    private boolean bridging = false;
    private int pickSlot = 0;
    private int sneakTicks = 0;

    private List<Pathfinder.Node> path = null;
    private int pathIdx = 0;
    private int repaths = 0;

    private long mineStart, noTargetSince, lastTp, lastProgressTime, nextScan, wpBlockedUntil, lastPlace;
    private double lastTravelDist = Double.MAX_VALUE;

    @Mod.EventHandler
    public void init(FMLInitializationEvent e) {
        for (KeyBinding k : new KeyBinding[]{kToggle, kAddWp, kClearWp, kUndoWp, kAotv, kBridge})
            ClientRegistry.registerKeyBinding(k);
        ClientCommandHandler.instance.registerCommand(new HsmCommand());
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void msg(Minecraft mc, String s) {
        if (mc.thePlayer != null)
            mc.thePlayer.addChatMessage(new ChatComponentText("\u00a7b[GemMiner] \u00a7f" + s));
    }

    private void dbg(String s) {
        if (debug) msg(Minecraft.getMinecraft(), "\u00a77[dbg] " + s);
    }

    // ================= Block matching =================
    private EnumDyeColor colorOf(IBlockState s) {
        Block b = s.getBlock();
        if (b == Blocks.stained_glass) return s.getValue(BlockStainedGlass.COLOR);
        if (b == Blocks.stained_glass_pane) return s.getValue(BlockStainedGlassPane.COLOR);
        return null;
    }

    /** Glass panes change their state with neighbours, so match glass by block + colour. */
    private boolean isReg(IBlockState s) {
        if (registered.contains(s)) return true;
        EnumDyeColor c = colorOf(s);
        if (c == null) return false;
        for (IBlockState r : registered)
            if (r.getBlock() == s.getBlock() && colorOf(r) == c) return true;
        return false;
    }

    // ================= Command: /hsm =================
    private class HsmCommand extends CommandBase {
        @Override public String getCommandName() { return "hsm"; }
        @Override public String getCommandUsage(ICommandSender s) { return "/hsm <set|clear|list|speed|range|preset>"; }
        @Override public int getRequiredPermissionLevel() { return 0; }
        @Override public boolean canCommandSenderUseCommand(ICommandSender s) { return true; }

        @Override
        public List<String> addTabCompletionOptions(ICommandSender s, String[] args, BlockPos pos) {
            if (args.length == 2 && args[0].equalsIgnoreCase("preset")) {
                List<String> names = new ArrayList<String>();
                for (EnumDyeColor c : EnumDyeColor.values()) names.add(c.getName());
                return getListOfStringsMatchingLastWord(args, names.toArray(new String[0]));
            }
            return getListOfStringsMatchingLastWord(args, "set", "clear", "list", "speed", "range", "preset", "debug", "dig");
        }

        @Override
        public void processCommand(ICommandSender sender, String[] args) throws CommandException {
            Minecraft mc = Minecraft.getMinecraft();
            if (args.length == 0) { msg(mc, "Use: /hsm set | clear | list | speed | range | preset"); return; }

            String sub = args[0].toLowerCase();
            if (sub.equals("set")) {
                MovingObjectPosition mop = mc.objectMouseOver;
                if (mop != null && mop.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
                    IBlockState bs = mc.theWorld.getBlockState(mop.getBlockPos());
                    if (registered.add(bs))
                        msg(mc, "Registered: " + bs.getBlock().getLocalizedName() + " (total " + registered.size() + ")");
                    else
                        msg(mc, "Ye block pehle se registered hai");
                } else {
                    msg(mc, "Crosshair kisi block pe rakh ke command chalao");
                }
            } else if (sub.equals("clear")) {
                registered.clear();
                skipUntil.clear();
                msg(mc, "Registered blocks cleared");
            } else if (sub.equals("list")) {
                if (registered.isEmpty()) msg(mc, "Koi block registered nahi hai");
                else for (IBlockState s : registered)
                    msg(mc, "- " + s.getBlock().getLocalizedName() + " " + s);
            } else if (sub.equals("speed")) {
                if (args.length < 2) { msg(mc, "Rotation speed: " + rotMult + " (use /hsm speed 0.5-6)"); return; }
                try {
                    rotMult = Math.max(0.5f, Math.min(6f, Float.parseFloat(args[1])));
                    msg(mc, "Rotation speed set: " + rotMult);
                } catch (NumberFormatException ex) { msg(mc, "Number likho, jaise /hsm speed 3"); }
            } else if (sub.equals("range")) {
                if (args.length < 2) { msg(mc, "Scan range: " + farRadius + " (use /hsm range 4-32)"); return; }
                try {
                    farRadius = Math.max(4, Math.min(32, Integer.parseInt(args[1])));
                    msg(mc, "Scan range set: " + farRadius);
                } catch (NumberFormatException ex) { msg(mc, "Number likho, jaise /hsm range 12"); }
            } else if (sub.equals("dig")) {
                dig = !dig;
                msg(mc, "Dig (gem ke samne ka block todna): " + (dig ? "ON" : "OFF"));
            } else if (sub.equals("debug")) {
                debug = !debug;
                msg(mc, "Debug: " + (debug ? "ON" : "OFF"));
            } else if (sub.equals("preset")) {
                if (args.length < 2) {
                    StringBuilder sb = new StringBuilder();
                    for (EnumDyeColor c : EnumDyeColor.values()) sb.append(c.getName()).append(" ");
                    msg(mc, "Use /hsm preset <color>. Colors: " + sb);
                    return;
                }
                for (EnumDyeColor c : EnumDyeColor.values()) {
                    if (c.getName().equalsIgnoreCase(args[1])) {
                        registered.add(Blocks.stained_glass.getDefaultState().withProperty(BlockStainedGlass.COLOR, c));
                        registered.add(Blocks.stained_glass_pane.getDefaultState().withProperty(BlockStainedGlassPane.COLOR, c));
                        msg(mc, "Registered glass + pane: " + c.getName() + " (total " + registered.size() + ")");
                        return;
                    }
                }
                msg(mc, "Color nahi mila. /hsm preset likh ke list dekho");
            } else {
                msg(mc, "Use: /hsm set | clear | list | speed | range | preset");
            }
        }
    }

    // ================= Path goals =================
    private class GemGoal implements Pathfinder.Goal {
        final BlockPos b;
        GemGoal(BlockPos b) { this.b = b; }
        private double dist(BlockPos p) {
            double dx = b.getX() + 0.5 - (p.getX() + 0.5);
            double dy = b.getY() + 0.5 - (p.getY() + 1.62);
            double dz = b.getZ() + 0.5 - (p.getZ() + 0.5);
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        private boolean los(BlockPos p) {
            Minecraft mc = Minecraft.getMinecraft();
            Vec3 eye = new Vec3(p.getX() + 0.5, p.getY() + 1.62, p.getZ() + 0.5);
            Vec3 c = new Vec3(b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5);
            MovingObjectPosition mop = mc.theWorld.rayTraceBlocks(eye, c, false, true, false);
            return mop != null && mop.getBlockPos() != null && mop.getBlockPos().equals(b);
        }
        public boolean reached(BlockPos p) { return dist(p) <= arriveReach && (dig || los(p)); }
        public double heuristic(BlockPos p) { return Math.max(0, dist(p) - arriveReach); }
    }

    private class WpGoal implements Pathfinder.Goal {
        final BlockPos w;
        WpGoal(BlockPos w) { this.w = w; }
        public boolean reached(BlockPos p) {
            return Math.abs(p.getX() - w.getX()) + Math.abs(p.getZ() - w.getZ()) <= 1
                    && Math.abs(p.getY() - w.getY()) <= 1;
        }
        public double heuristic(BlockPos p) {
            return Math.abs(p.getX() - w.getX()) + Math.abs(p.getZ() - w.getZ()) + Math.abs(p.getY() - w.getY());
        }
    }

    // ================= Main tick =================
    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.theWorld == null) {
            state = State.IDLE;
            return;
        }

        if (mc.currentScreen == null) handleKeys(mc);
        if (state == State.IDLE) return;

        // Failsafes
        if (mc.currentScreen != null) { stop(mc, "GUI opened"); return; }
        Vec3 pos = mc.thePlayer.getPositionVector();
        boolean recentTp = System.currentTimeMillis() - lastTp < 1500;
        if (!recentTp && lastPos != null && pos.distanceTo(lastPos) > 3.0) {
            stop(mc, "Teleport/rubberband");
            return;
        }
        lastPos = pos;

        KeyBinding attack = mc.gameSettings.keyBindAttack;

        switch (state) {
            case SELECT: {
                releaseMoveKeys(mc);
                setSneak(mc, false);
                mc.thePlayer.inventory.currentItem = pickSlot;
                KeyBinding.setKeyBindState(attack.getKeyCode(), false);
                long now = System.currentTimeMillis();

                // 1) reach ke andar jo block aim ke sabse paas ho
                target = pickTarget(mc);
                if (target == null && dig) target = pickObstruction(mc); // gem ke samne ka block
                if (target != null) {
                    targetBlock = mc.theWorld.getBlockState(target).getBlock();
                    noTargetSince = 0;
                    state = State.AIM;
                    break;
                }

                // 2) aas-paas gemstone scan karke raasta banao
                if (now >= nextScan) {
                    nextScan = now + 400;
                    if (tryStartGemPath(mc, now)) { noTargetSince = 0; break; }
                }

                // 3) kuch nahi mila -> waypoint
                if (noTargetSince == 0) noTargetSince = now;
                if (now - noTargetSince > noBlockWaitMs && now > wpBlockedUntil && !waypoints.isEmpty()) {
                    if (wpIndex >= waypoints.size()) wpIndex = 0;
                    if (waypoints.size() > 1) {
                        noTargetSince = 0;
                        nextWaypoint();
                    } else {
                        BlockPos wp = waypoints.get(0);
                        double dx = wp.getX() + 0.5 - mc.thePlayer.posX;
                        double dz = wp.getZ() + 0.5 - mc.thePlayer.posZ;
                        if (Math.sqrt(dx * dx + dz * dz) > 2.5) { noTargetSince = 0; beginWaypoint(); }
                    }
                }
                break;
            }
            case AIM: {
                if (target == null || mc.theWorld.getBlockState(target).getBlock() != targetBlock) { state = State.SELECT; break; }
                float[] rot = getRotations(mc, target);
                smoothRotate(mc, rot[0], rot[1], 0.25f);
                if (isLookingAt(mc, rot[0], rot[1], 3f)) {
                    mineStart = System.currentTimeMillis();
                    state = State.MINE;
                }
                break;
            }
            case MINE: {
                if (target == null) { state = State.SELECT; break; }
                float[] rot = getRotations(mc, target);
                smoothRotate(mc, rot[0], rot[1], 0.25f);
                KeyBinding.setKeyBindState(attack.getKeyCode(), true);

                boolean broken = mc.theWorld.getBlockState(target).getBlock() != targetBlock;
                boolean timeout = System.currentTimeMillis() - mineStart > mineTimeoutMs;
                if (broken || timeout) {
                    if (timeout) skipUntil.put(target, System.currentTimeMillis() + 10000);
                    KeyBinding.setKeyBindState(attack.getKeyCode(), false);
                    target = null;
                    state = State.SELECT;
                }
                break;
            }
            case PATH:
                doPath(mc);
                break;
            default:
                break;
        }
    }

    // ================= Keys =================
    private void handleKeys(Minecraft mc) {
        if (kToggle.isPressed()) {
            if (state == State.IDLE) {
                if (registered.isEmpty()) {
                    msg(mc, "Pehle /hsm set ya /hsm preset se block register karo");
                } else {
                    pickSlot = mc.thePlayer.inventory.currentItem;
                    lastPos = null;
                    noTargetSince = 0;
                    nextScan = 0;
                    wpBlockedUntil = 0;
                    state = State.SELECT;
                    msg(mc, "Started");
                }
            } else {
                stop(mc, "Manual");
            }
        }
        if (kAddWp.isPressed()) {
            waypoints.add(mc.thePlayer.getPosition());
            msg(mc, "Waypoint " + waypoints.size() + " added");
        }
        if (kUndoWp.isPressed() && !waypoints.isEmpty()) {
            waypoints.remove(waypoints.size() - 1);
            wpIndex = 0;
            msg(mc, "Last waypoint removed (" + waypoints.size() + " left)");
        }
        if (kClearWp.isPressed()) {
            waypoints.clear();
            wpIndex = 0;
            msg(mc, "Waypoints cleared");
        }
        if (kAotv.isPressed()) {
            useAotv = !useAotv;
            msg(mc, "AOTV mode: " + (useAotv ? "ON" : "OFF"));
        }
        if (kBridge.isPressed()) {
            bridging = !bridging;
            msg(mc, "Bridging (gap par block rakhna): " + (bridging ? "ON" : "OFF"));
        }
    }

    // ================= Targeting (aim ke sabse paas) =================
    private boolean canSee(Minecraft mc, BlockPos pos) {
        Vec3 eye = mc.thePlayer.getPositionEyes(1f);
        Vec3 center = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        MovingObjectPosition mop = mc.theWorld.rayTraceBlocks(eye, center, false, true, false);
        return mop != null && mop.getBlockPos() != null && mop.getBlockPos().equals(pos);
    }

    private BlockPos pickTarget(Minecraft mc) {
        BlockPos p = mc.thePlayer.getPosition();
        Vec3 eye = mc.thePlayer.getPositionEyes(1f);
        long now = System.currentTimeMillis();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        int r = 5;
        for (int x = -r; x <= r; x++)
            for (int y = -r; y <= r; y++)
                for (int z = -r; z <= r; z++) {
                    BlockPos b = p.add(x, y, z);
                    Long until = skipUntil.get(b);
                    if (until != null && until > now) continue;
                    if (!isReg(mc.theWorld.getBlockState(b))) continue;
                    double dist = eye.distanceTo(new Vec3(b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5));
                    if (dist > mineReach) continue;
                    if (!canSee(mc, b)) continue;
                    float[] rot = getRotations(mc, b);
                    double yd = Math.abs(MathHelper.wrapAngleTo180_float(rot[0] - mc.thePlayer.rotationYaw));
                    double pd = Math.abs(rot[1] - mc.thePlayer.rotationPitch);
                    double score = yd + pd + dist * 0.5;
                    if (score < bestScore) { bestScore = score; best = b; }
                }
        return best;
    }

    /** Gem reach me hai par dikh nahi raha: beech ka pehla block dhundho jo todna hai. */
    private BlockPos pickObstruction(Minecraft mc) {
        BlockPos p = mc.thePlayer.getPosition();
        Vec3 eye = mc.thePlayer.getPositionEyes(1f);
        long now = System.currentTimeMillis();
        BlockPos gem = null;
        double gemDist = Double.MAX_VALUE;
        int r = 5;
        for (int x = -r; x <= r; x++)
            for (int y = -r; y <= r; y++)
                for (int z = -r; z <= r; z++) {
                    BlockPos b = p.add(x, y, z);
                    Long until = skipUntil.get(b);
                    if (until != null && until > now) continue;
                    if (!isReg(mc.theWorld.getBlockState(b))) continue;
                    double d = eye.distanceTo(new Vec3(b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5));
                    if (d > mineReach || d >= gemDist) continue;
                    if (canSee(mc, b)) continue;
                    gem = b;
                    gemDist = d;
                }
        if (gem == null) return null;

        Vec3 c = new Vec3(gem.getX() + 0.5, gem.getY() + 0.5, gem.getZ() + 0.5);
        MovingObjectPosition mop = mc.theWorld.rayTraceBlocks(eye, c, false, true, false);
        BlockPos ob = mop != null ? mop.getBlockPos() : null;
        boolean ok = ob != null && !ob.equals(gem);
        if (ok) {
            IBlockState os = mc.theWorld.getBlockState(ob);
            Block b = os.getBlock();
            Long u = skipUntil.get(ob);
            ok = !isReg(os)
                    && b.getBlockHardness(mc.theWorld, ob) >= 0
                    && !b.getMaterial().isLiquid()
                    && !(u != null && u > now)
                    && eye.distanceTo(new Vec3(ob.getX() + 0.5, ob.getY() + 0.5, ob.getZ() + 0.5)) <= mineReach;
            if (ok) dbg("Gem ke samne ka block todunga: " + b.getLocalizedName());
        }
        if (!ok) {
            dbg("Samne ka block tod nahi sakta, gem skip");
            skipUntil.put(gem, now + 10000);
            return null;
        }
        return ob;
    }

    private List<BlockPos> findCandidates(Minecraft mc, int r, int limit) {
        final BlockPos p = mc.thePlayer.getPosition();
        long now = System.currentTimeMillis();
        List<BlockPos> all = new ArrayList<BlockPos>();
        for (int x = -r; x <= r; x++)
            for (int y = -r; y <= r; y++)
                for (int z = -r; z <= r; z++) {
                    BlockPos b = p.add(x, y, z);
                    Long until = skipUntil.get(b);
                    if (until != null && until > now) continue;
                    if (isReg(mc.theWorld.getBlockState(b))) all.add(b);
                }
        Collections.sort(all, new Comparator<BlockPos>() {
            public int compare(BlockPos a, BlockPos b) { return Double.compare(p.distanceSq(a), p.distanceSq(b)); }
        });
        return all.size() > limit ? new ArrayList<BlockPos>(all.subList(0, limit)) : all;
    }

    private boolean tryStartGemPath(Minecraft mc, long now) {
        List<BlockPos> cands = findCandidates(mc, farRadius, 3);
        if (cands.isEmpty() && now - lastEmptyDbg > 3000) {
            lastEmptyDbg = now;
            dbg("Range " + farRadius + " me koi registered gemstone nahi");
        }
        for (BlockPos c : cands) {
            List<Pathfinder.Node> pth = Pathfinder.find(mc.theWorld, mc.thePlayer.getPosition(),
                    new GemGoal(c), bridging, 2500);
            if (pth != null && !pth.isEmpty()) {
                dbg("Path mila: " + pth.size() + " steps -> " + c);
                gemTarget = c;
                path = pth;
                pathIdx = 0;
                purpose = Purpose.GEM;
                repaths = 0;
                initProgress(now);
                state = State.PATH;
                return true;
            }
            dbg("Raasta nahi mila -> " + c + " (skip 10s)");
            skipUntil.put(c, now + 10000);
        }
        return false;
    }

    // ================= Path following =================
    private void initProgress(long now) {
        lastTravelDist = Double.MAX_VALUE;
        lastProgressTime = now;
        sneakTicks = 0;
    }

    private void beginWaypoint() {
        purpose = Purpose.WAYPOINT;
        path = null;
        pathIdx = 0;
        repaths = 0;
        gemTarget = null;
        initProgress(System.currentTimeMillis());
        state = State.PATH;
    }

    private void nextWaypoint() {
        if (waypoints.isEmpty()) { state = State.SELECT; return; }
        wpIndex = (wpIndex + 1) % waypoints.size();
        beginWaypoint();
    }

    private void endPath(Minecraft mc) {
        path = null;
        pathIdx = 0;
        gemTarget = null;
        releaseMoveKeys(mc);
    }

    private void pathFailed(Minecraft mc) {
        long now = System.currentTimeMillis();
        releaseMoveKeys(mc);
        dbg("Path failed (" + purpose + ") repaths=" + repaths);
        if (purpose == Purpose.GEM) {
            if (repaths < 1 && gemTarget != null) { repaths++; path = null; return; }
            if (gemTarget != null) skipUntil.put(gemTarget, now + 10000);
            endPath(mc);
            state = State.SELECT;
        } else {
            if (repaths < 1) { repaths++; path = null; return; }
            msg(mc, "Waypoint tak raasta nahi mila, skip");
            endPath(mc);
            if (waypoints.size() > 1) nextWaypoint();
            else { wpBlockedUntil = now + 10000; state = State.SELECT; }
        }
    }

    private void doPath(Minecraft mc) {
        long now = System.currentTimeMillis();

        if (purpose == Purpose.GEM) {
            if (gemTarget == null || !isReg(mc.theWorld.getBlockState(gemTarget))) {
                endPath(mc); state = State.SELECT; return;
            }
            // raaste me koi block reach me aa gaya -> turant mine
            if (pickTarget(mc) != null) { endPath(mc); state = State.SELECT; return; }
        } else {
            if (waypoints.isEmpty()) { endPath(mc); state = State.SELECT; return; }
            if (wpIndex >= waypoints.size()) wpIndex = 0;
            BlockPos wp = waypoints.get(wpIndex);
            double dx = wp.getX() + 0.5 - mc.thePlayer.posX;
            double dz = wp.getZ() + 0.5 - mc.thePlayer.posZ;
            double hd = Math.sqrt(dx * dx + dz * dz);
            if (useAotv && hd > tpRange + 1.0) {
                if (hd < lastTravelDist - 0.5) { lastTravelDist = hd; lastProgressTime = now; }
                else if (now - lastProgressTime > 6000) {
                    releaseMoveKeys(mc);
                    setSneak(mc, false);
                    msg(mc, "AOTV hop fail, waypoint skip");
                    endPath(mc);
                    if (waypoints.size() > 1) nextWaypoint();
                    else { wpBlockedUntil = now + 10000; state = State.SELECT; }
                    return;
                }
                aotvHop(mc, wp);
                return;
            }
        }

        if (path == null) {
            Pathfinder.Goal g = (purpose == Purpose.GEM) ? new GemGoal(gemTarget) : new WpGoal(waypoints.get(wpIndex));
            path = Pathfinder.find(mc.theWorld, mc.thePlayer.getPosition(), g, bridging, 2500);
            pathIdx = 0;
            initProgress(now);
            if (path == null) { dbg("A* ko raasta nahi mila"); repaths = 99; pathFailed(mc); return; }
            if (path.isEmpty()) {
                if (purpose == Purpose.GEM && gemTarget != null) skipUntil.put(gemTarget, now + 10000);
                endPath(mc);
                state = State.SELECT;
                return;
            }
        }

        int r = followPath(mc, now);
        if (r == 1) { endPath(mc); noTargetSince = 0; state = State.SELECT; }
        else if (r == 2) pathFailed(mc);
    }

    /** 0 = running, 1 = done, 2 = failed. Sirf forward chalta hai (back/strafe nahi). */
    private int followPath(Minecraft mc, long now) {
        if (path == null || pathIdx >= path.size()) return 1;
        Pathfinder.Node n = path.get(pathIdx);

        double cx = n.pos.getX() + 0.5, cz = n.pos.getZ() + 0.5;
        double dx = cx - mc.thePlayer.posX, dz = cz - mc.thePlayer.posZ;
        double hd = Math.sqrt(dx * dx + dz * dz);
        double dy = n.pos.getY() - mc.thePlayer.posY;

        if (hd > 4.0 || Math.abs(dy) > 4.5) { dbg("Raaste se bahut door (hd=" + (int) hd + ")"); return 2; }

        if (hd < 0.5 && Math.abs(dy) < 1.2) {
            pathIdx++;
            lastTravelDist = Double.MAX_VALUE;
            lastProgressTime = now;
            if (pathIdx >= path.size()) { releaseMoveKeys(mc); return 1; }
            n = path.get(pathIdx);
            cx = n.pos.getX() + 0.5; cz = n.pos.getZ() + 0.5;
            dx = cx - mc.thePlayer.posX; dz = cz - mc.thePlayer.posZ;
            hd = Math.sqrt(dx * dx + dz * dz);
        }

        // Stuck check
        if (hd < lastTravelDist - 0.2) { lastTravelDist = hd; lastProgressTime = now; }
        else if (now - lastProgressTime > 2500) { dbg("Stuck: step " + pathIdx + "/" + path.size() + " " + n.move); return 2; }

        setSneak(mc, false);

        // Bridging: gap par block rakho
        if (n.move == Pathfinder.Move.BRIDGE && !Pathfinder.solid(mc.theWorld, n.pos.down())) {
            releaseMoveKeys(mc);
            int slot = findBlockSlot(mc);
            if (slot == -1) { msg(mc, "Hotbar me placeable block nahi mila"); return 2; }
            mc.thePlayer.inventory.currentItem = slot;

            BlockPos cur = mc.thePlayer.getPosition();
            int ddx = n.pos.getX() - cur.getX(), ddz = n.pos.getZ() - cur.getZ();
            if (Math.abs(ddx) + Math.abs(ddz) == 1 && Pathfinder.solid(mc.theWorld, cur.down())) {
                float yaw = (float) (Math.atan2(dz, dx) * 180 / Math.PI) - 90f;
                smoothRotate(mc, yaw, 60f, 0.4f);
                if (now - lastPlace > 200) {
                    EnumFacing face = ddx == 1 ? EnumFacing.EAST : ddx == -1 ? EnumFacing.WEST
                            : ddz == 1 ? EnumFacing.SOUTH : EnumFacing.NORTH;
                    BlockPos hit = cur.down();
                    Vec3 hv = new Vec3(hit.getX() + 0.5 + 0.5 * ddx, hit.getY() + 0.5, hit.getZ() + 0.5 + 0.5 * ddz);
                    ItemStack held = mc.thePlayer.getHeldItem();
                    if (mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, held, hit, face, hv))
                        mc.thePlayer.swingItem();
                    lastPlace = now;
                }
            }
            return 0;
        }

        mc.thePlayer.inventory.currentItem = pickSlot;

        float yaw = (float) (Math.atan2(dz, dx) * 180 / Math.PI) - 90f;
        smoothRotate(mc, yaw, 0f, 0.3f);
        float yawDiff = Math.abs(MathHelper.wrapAngleTo180_float(yaw - mc.thePlayer.rotationYaw));
        boolean aligned = yawDiff < 20f;

        KeyBinding.setKeyBindState(mc.gameSettings.keyBindBack.getKeyCode(), false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindLeft.getKeyCode(), false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindRight.getKeyCode(), false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), aligned);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(),
                aligned && hd > 2.0 && n.move == Pathfinder.Move.FLAT);
        boolean needJump = (n.move == Pathfinder.Move.UP && hd < 1.7)
                || mc.thePlayer.isCollidedHorizontally;
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(),
                needJump && mc.thePlayer.onGround);
        return 0;
    }

    private void aotvHop(Minecraft mc, BlockPos wp) {
        int slot = findAotv(mc);
        if (slot == -1) { stop(mc, "AOTV hotbar me nahi mila"); return; }

        releaseMoveKeys(mc);
        mc.thePlayer.inventory.currentItem = slot;
        setSneak(mc, true);
        sneakTicks++;

        float[] rot = getRotations(mc, wp);
        smoothRotate(mc, rot[0], rot[1], 0.3f);

        long now = System.currentTimeMillis();
        if (sneakTicks >= 3 && now - lastTp > 600 && isLookingAt(mc, rot[0], rot[1], 2f)) {
            ItemStack held = mc.thePlayer.getHeldItem();
            mc.playerController.sendUseItem(mc.thePlayer, mc.theWorld, held);
            lastTp = now;
        }
    }

    // ================= Rotation =================
    private float[] getRotations(Minecraft mc, BlockPos t) {
        Vec3 eye = mc.thePlayer.getPositionEyes(1f);
        double dx = t.getX() + 0.5 - eye.xCoord;
        double dy = t.getY() + 0.5 - eye.yCoord;
        double dz = t.getZ() + 0.5 - eye.zCoord;
        float yaw = (float) (Math.atan2(dz, dx) * 180 / Math.PI) - 90f;
        float pitch = (float) -(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * 180 / Math.PI);
        return new float[]{yaw, pitch};
    }

    private void smoothRotate(Minecraft mc, float tYaw, float tPitch, float speed) {
        float yawDiff = MathHelper.wrapAngleTo180_float(tYaw - mc.thePlayer.rotationYaw);
        float pitchDiff = tPitch - mc.thePlayer.rotationPitch;
        float eff = Math.min(1f, speed * rotMult);
        float maxStep = 12f * rotMult;
        mc.thePlayer.rotationYaw += MathHelper.clamp_float(yawDiff * eff, -maxStep, maxStep);
        mc.thePlayer.rotationPitch = MathHelper.clamp_float(
                mc.thePlayer.rotationPitch + MathHelper.clamp_float(pitchDiff * eff, -maxStep, maxStep),
                -90f, 90f);
    }

    private boolean isLookingAt(Minecraft mc, float tYaw, float tPitch, float tol) {
        float yd = Math.abs(MathHelper.wrapAngleTo180_float(tYaw - mc.thePlayer.rotationYaw));
        float pd = Math.abs(tPitch - mc.thePlayer.rotationPitch);
        return yd < tol && pd < tol;
    }

    // ================= Helpers =================
    private int findAotv(Minecraft mc) {
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.thePlayer.inventory.getStackInSlot(i);
            if (s != null && s.getDisplayName().contains("Aspect of the")) return i;
        }
        return -1;
    }

    private int findBlockSlot(Minecraft mc) {
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.thePlayer.inventory.getStackInSlot(i);
            if (s == null || s.stackSize <= 0) continue;
            Item it = s.getItem();
            if (it instanceof ItemBlock && ((ItemBlock) it).getBlock().isFullCube()) return i;
        }
        return -1;
    }

    private void setSneak(Minecraft mc, boolean on) {
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindSneak.getKeyCode(), on);
        if (!on) sneakTicks = 0;
    }

    private void releaseMoveKeys(Minecraft mc) {
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindBack.getKeyCode(), false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindLeft.getKeyCode(), false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindRight.getKeyCode(), false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(), false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), false);
    }

    private void stop(Minecraft mc, String reason) {
        state = State.IDLE;
        target = null;
        gemTarget = null;
        path = null;
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindAttack.getKeyCode(), false);
        releaseMoveKeys(mc);
        setSneak(mc, false);
        mc.thePlayer.inventory.currentItem = pickSlot;
        msg(mc, "Stopped: " + reason);
    }

    // ================= HUD =================
    @SubscribeEvent
    public void onHud(RenderGameOverlayEvent.Text e) {
        Minecraft mc = Minecraft.getMinecraft();
        mc.fontRendererObj.drawStringWithShadow("GemMiner: " + state
                + (state == State.PATH ? " (" + purpose + ")" : "")
                + " | Blocks: " + registered.size()
                + " | WP: " + (waypoints.isEmpty() ? "-" : (wpIndex + 1) + "/" + waypoints.size())
                + " | AOTV: " + (useAotv ? "ON" : "OFF")
                + " | Bridge: " + (bridging ? "ON" : "OFF")
                + " | Dig: " + (dig ? "ON" : "OFF"), 4, 4, 0x55FFFF);
    }
}
