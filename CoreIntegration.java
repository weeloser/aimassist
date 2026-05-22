package net.core.integration;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.text.Text;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

public class CoreIntegration implements ClientModInitializer {
    private static double syncRate = 0.35;
    private static double maxDist = 7;
    private static int aimMode = 3;
    private static boolean hvhMode = false;
    private static boolean predMode = true;
    private static boolean wallCheck = true;
    private static boolean invizMode = false;
    private static boolean hwAccel = false;
    private static boolean isActive = false;
    private static boolean isPanic = false;
    public static boolean seeGhosts = false;

    // Новые функции
    public static List<String> friends = new ArrayList<>();
    private static double fov = 85.0;

    // Система 5udar
    private static boolean udarEnabled = true;
    private static double udarRand = 85.0;
    private static long udarTimeoutMs = 0;
    private static long lastValidHitTime = 0;

    private static KeyBinding renderToggleKey;
    private static PlayerEntity currentTarget = null;

    @Override
    public void onInitializeClient() {
        loadConfig();
        // 86 = V
        renderToggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.render.chunk_borders",
                InputUtil.Type.KEYSYM,
                86,
                "key.categories.misc"
        ));

        ClientSendMessageEvents.ALLOW_CHAT.register(this::processRenderDirectives);
        ClientTickEvents.END_CLIENT_TICK.register(this::updateLogic);
        ClientTickEvents.START_CLIENT_TICK.register(this::onStartTick);
    }

    private void onStartTick(MinecraftClient client) {
        if (client.player == null || client.world == null) return;

        // Логика умного блокировщика ударов (Anti-Miss / 5udar)
        if (udarEnabled && !isPanic) {
            KeyBinding attackKey = client.options.attackKey;
            if (attackKey.isPressed()) {
                boolean isBlock = false;
                boolean isHitValid = false;
                boolean isMiss = true;

                HitResult target = client.crosshairTarget;
                if (target != null) {
                    if (target.getType() == HitResult.Type.BLOCK) {
                        isBlock = true;
                        isMiss = false;
                    } else if (target.getType() == HitResult.Type.ENTITY) {
                        Entity entity = ((EntityHitResult) target).getEntity();
                        if (entity instanceof PlayerEntity) {
                            PlayerEntity playerTarget = (PlayerEntity) entity;
                            String name = playerTarget.getName().getString().toLowerCase();
                            double dist = client.player.distanceTo(playerTarget);

                            if (friends.contains(name)) {
                                isMiss = true; // Удар по другу — мимо
                            } else if (dist > 3.0) {
                                isMiss = true; // Дальше 3 блоков — мимо
                            } else {
                                isMiss = false;
                                isHitValid = true; // Валидный удар
                            }
                        } else {
                            isMiss = false; // По мобам бьем как обычно
                        }
                    }
                }

                long now = System.currentTimeMillis();
                if (isHitValid) {
                    lastValidHitTime = now;
                }

                boolean playersNearby = false;
                for (PlayerEntity p : client.world.getPlayers()) {
                    if (p != client.player) {
                        if (!friends.contains(p.getName().getString().toLowerCase())) {
                            if (client.player.distanceTo(p) <= 3.0) {
                                playersNearby = true;
                                break;
                            }
                        }
                    }
                }

                // Включаемся если таймаут не истек (либо таймаут=0), И есть игроки рядом
                boolean awake = (udarTimeoutMs == 0 || (now - lastValidHitTime <= udarTimeoutMs)) && playersNearby;

                if (awake && isMiss && !isBlock) {
                    if (Math.random() * 100.0 < udarRand) {
                        attackKey.setPressed(false);
                        while (attackKey.wasPressed()) {} // Поглощаем ивенты клика
                    }
                }
            }
        }
    }

    private static void loadConfig() {
        File configFile = new File(FabricLoader.getInstance().getGameDir().toFile(), "options.txt");
        if (!configFile.exists()) return;
        try {
            for (String line : Files.readAllLines(configFile.toPath())) {
                String[] parts = line.split(":", 2);
                if (parts.length < 2) continue;
                String val = parts[1];
                switch (parts[0]) {
                    case "fpsSync": syncRate = Double.parseDouble(val); break;
                    case "renderMax": maxDist = Double.parseDouble(val); break;
                    case "renderMode": aimMode = Integer.parseInt(val); break;
                    case "fastRender": hvhMode = Boolean.parseBoolean(val); break;
                    case "cachePredict": predMode = Boolean.parseBoolean(val); break;
                    case "occlusionCulling": wallCheck = Boolean.parseBoolean(val); break;
                    case "alphaTesting": invizMode = Boolean.parseBoolean(val); break;
                    case "gammaCorrection": seeGhosts = Boolean.parseBoolean(val); break;
                    case "fovVal": fov = Double.parseDouble(val); break;
                    case "friendList":
                        friends.clear();
                        if (!val.trim().isEmpty()) friends.addAll(Arrays.asList(val.split(",")));
                        break;
                    case "udarOn": udarEnabled = Boolean.parseBoolean(val); break;
                    case "udarRnd": udarRand = Double.parseDouble(val); break;
                    case "udarTime": udarTimeoutMs = Long.parseLong(val); break;
                }
            }
        } catch (Exception ignored) {}
    }

    private static void saveConfig() {
        File configFile = new File(FabricLoader.getInstance().getGameDir().toFile(), "options.txt");
        try {
            List<String> lines = configFile.exists() ? Files.readAllLines(configFile.toPath()) : new ArrayList<>();
            Map<String, String> map = new HashMap<>();

            map.put("fpsSync", String.valueOf(syncRate));
            map.put("renderMax", String.valueOf(maxDist));
            map.put("renderMode", String.valueOf(aimMode));
            map.put("fastRender", String.valueOf(hvhMode));
            map.put("cachePredict", String.valueOf(predMode));
            map.put("occlusionCulling", String.valueOf(wallCheck));
            map.put("alphaTesting", String.valueOf(invizMode));
            map.put("gammaCorrection", String.valueOf(seeGhosts));
            map.put("fovVal", String.valueOf(fov));
            map.put("friendList", String.join(",", friends));
            map.put("udarOn", String.valueOf(udarEnabled));
            map.put("udarRnd", String.valueOf(udarRand));
            map.put("udarTime", String.valueOf(udarTimeoutMs));

            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String[] parts = line.split(":", 2);
                if (parts.length > 0 && map.containsKey(parts[0])) {
                    lines.set(i, parts[0] + ":" + map.get(parts[0]));
                    map.remove(parts[0]);
                }
            }
            for (Map.Entry<String, String> entry : map.entrySet()) {
                lines.add(entry.getKey() + ":" + entry.getValue());
            }
            Files.write(configFile.toPath(), lines, new OpenOption[0]);
        } catch (Exception ignored) {}
    }

    private void msg(String text) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal("\u00a77" + text), false);
        }
    }

    private void clearChat() {
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.inGameHud != null && client.inGameHud.getChatHud() != null) {
                client.inGameHud.getChatHud().clear(false);
            }
        } catch (Exception ignored) {}
    }

    private boolean processRenderDirectives(String buffer) {
        String lower = buffer.trim().toLowerCase();
        if (!lower.startsWith("5")) return true;

        String[] args = lower.split(" ");
        if (args.length == 0) return false;

        if (isPanic) {
            if (lower.equals("5vse")) {
                isPanic = false;
                this.msg("Debug: state_restored");
            }
            return false;
        }

        if (lower.equals("5panic")) {
            isPanic = true;
            isActive = false;
            invizMode = false;
            seeGhosts = false;
            saveConfig();
            this.clearChat();
            return false;
        }

        if (args[0].equals("5help")) {
            this.msg("cmd list:");
            this.msg("5aim[xit/gor/ver/ultra] -> " + this.getAimModeName());
            this.msg("5mag[1-100] -> " + (int)(syncRate * 100));
            this.msg("5rad[x] -> " + maxDist);
            this.msg("5pred [on/off] -> " + predMode);
            this.msg("5wall [on/off] -> " + wallCheck);
            this.msg("5inviz [on/off] -> " + invizMode);
            this.msg("5see[on/off] -> " + seeGhosts);
            this.msg("5hvh[on/off] -> " + hvhMode);
            this.msg("5fov [число] -> " + fov);
            this.msg("5friend add/delete/list [ник]");
            this.msg("5udar [on/off/rand/timeout]");
            this.msg("5chat -> clear");
            this.msg("5panic -> hide all");
            return false;
        }

        if (lower.equals("5chat")) {
            this.clearChat();
            return false;
        }

        try {
            if (args[0].equals("5friend")) {
                if (args.length > 1) {
                    if (args[1].equals("add") && args.length > 2) {
                        String name = args[2].toLowerCase();
                        if (!friends.contains(name)) friends.add(name);
                        this.msg("Friend added: " + name);
                    } else if (args[1].equals("delete") && args.length > 2) {
                        String name = args[2].toLowerCase();
                        friends.remove(name);
                        this.msg("Friend removed: " + name);
                    } else if (args[1].equals("list")) {
                        this.msg("Friends: " + String.join(", ", friends));
                    }
                    saveConfig();
                }
                return false;
            }

            if (args[0].equals("5fov") && args.length > 1) {
                fov = Double.parseDouble(args[1]);
                saveConfig();
                this.msg("fov: " + fov);
                return false;
            }

            if (args[0].equals("5udar") && args.length > 1) {
                if (args[1].equals("on")) {
                    udarEnabled = true;
                    this.msg("udar: on");
                } else if (args[1].equals("off")) {
                    udarEnabled = false;
                    this.msg("udar: off");
                } else if (args[1].equals("rand") && args.length > 2) {
                    udarRand = Double.parseDouble(args[2]);
                    this.msg("udar rand: " + udarRand + "%");
                } else if (args[1].equals("timeout") && args.length > 2) {
                    String t = args[2];
                    long mult = 1000;
                    if (t.endsWith("s")) { mult = 1000; t = t.substring(0, t.length() - 1); }
                    else if (t.endsWith("m")) { mult = 60000; t = t.substring(0, t.length() - 1); }
                    else if (t.endsWith("h")) { mult = 3600000; t = t.substring(0, t.length() - 1); }
                    udarTimeoutMs = (long) (Double.parseDouble(t) * mult);
                    this.msg("udar timeout ms: " + udarTimeoutMs);
                }
                saveConfig();
                return false;
            }

            if (lower.startsWith("5mag")) {
                syncRate = MathHelper.clamp(Double.parseDouble(lower.substring(4).trim()) / 100, 0.01, 1);
                saveConfig();
                this.msg("mag: " + (int)(syncRate * 100));
                return false;
            }
            if (lower.startsWith("5rad")) {
                maxDist = Double.parseDouble(lower.substring(4).trim());
                saveConfig();
                this.msg("rad: " + maxDist);
                return false;
            }

            if (lower.equals("5aim xit")) { aimMode = 0; saveConfig(); this.msg("aim: xit"); return false; }
            if (lower.equals("5aim gor")) { aimMode = 1; saveConfig(); this.msg("aim: gor"); return false; }
            if (lower.equals("5aim ver")) { aimMode = 2; saveConfig(); this.msg("aim: ver"); return false; }
            if (lower.equals("5aim ultra")) { aimMode = 3; saveConfig(); this.msg("aim: ultra"); return false; }

            if (lower.equals("5hvh on")) { hvhMode = true; saveConfig(); this.msg("hvh: true"); return false; }
            if (lower.equals("5hvh off")) { hvhMode = false; saveConfig(); this.msg("hvh: false"); return false; }

            if (lower.equals("5pred on")) { predMode = true; saveConfig(); this.msg("pred: true"); return false; }
            if (lower.equals("5pred off")) { predMode = false; saveConfig(); this.msg("pred: false"); return false; }

            if (lower.equals("5wall on")) { wallCheck = true; saveConfig(); this.msg("wall: true"); return false; }
            if (lower.equals("5wall off")) { wallCheck = false; saveConfig(); this.msg("wall: false"); return false; }

            if (lower.equals("5inviz on")) { invizMode = true; saveConfig(); this.msg("inviz: true"); return false; }
            if (lower.equals("5inviz off")) { invizMode = false; saveConfig(); this.msg("inviz: false"); return false; }

            if (lower.equals("5see on")) { seeGhosts = true; saveConfig(); this.msg("see: true"); return false; }
            if (lower.equals("5see off")) { seeGhosts = false; saveConfig(); this.msg("see: false"); return false; }

        } catch (Exception ignored) {}

        return false;
    }

    private String getAimModeName() {
        if (aimMode == 0) return "xit";
        if (aimMode == 1) return "gor";
        if (aimMode == 2) return "ver";
        return "ultra";
    }

    private boolean canSeeTarget(MinecraftClient client, PlayerEntity target) {
        if (client.player == null || client.world == null) return false;
        Vec3d start = client.player.getEyePos();
        Vec3d end = target.getEyePos();
        RaycastContext context = new RaycastContext(start, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, client.player);
        HitResult result = client.world.raycast(context);
        return result.getType() == HitResult.Type.MISS;
    }

    private boolean isValidTarget(MinecraftClient client, PlayerEntity p) {
        if (p == null || p == client.player || !p.isAlive() || p.isSpectator()) return false;
        if (friends.contains(p.getName().getString().toLowerCase())) return false; // Игнорируем друзей
        if (!invizMode && p.isInvisible()) return false;
        if (client.player.distanceTo(p) > maxDist) return false;
        if (wallCheck && !canSeeTarget(client, p)) return false;

        double dx = p.getX() - client.player.getX();
        double dz = p.getZ() - client.player.getZ();
        float iY = MathHelper.wrapDegrees((float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f);
        return Math.abs(MathHelper.wrapDegrees(iY - client.player.getYaw())) <= fov;
    }

    private void updateLogic(MinecraftClient client) {
        if (client.player == null || client.world == null) return;

        if (seeGhosts && !isPanic) {
            try {
                Scoreboard sb = client.world.getScoreboard();
                if (sb != null) {
                    Team t = sb.getTeam("z_level_cache");
                    if (t == null) {
                        t = sb.addTeam("z_level_cache");
                        t.setShowFriendlyInvisibles(true);
                    }
                    String pName = client.player.getName().getString();
                    if (sb.getScoreHolderTeam(pName) != t) {
                        sb.addScoreHolderToTeam(pName, t);
                    }
                    for (PlayerEntity p : client.world.getPlayers()) {
                        if (p.isInvisible()) {
                            String eName = p.getName().getString();
                            if (sb.getScoreHolderTeam(eName) != t) {
                                sb.addScoreHolderToTeam(eName, t);
                            }
                        }
                    }
                }
            } catch (Exception ignored) {} // Защита от ошибок скорборда
        } else {
            try {
                Scoreboard sb = client.world.getScoreboard();
                if (sb != null) {
                    Team t = sb.getTeam("z_level_cache");
                    if (t != null) {
                        sb.removeTeam(t);
                    }
                }
            } catch (Exception ignored) {}
        }

        while (renderToggleKey.wasPressed()) {
            if (!isPanic) {
                isActive = !isActive;
                if (!isActive) currentTarget = null;
            }
        }

        if (!isActive || isPanic) return;

        if (!isValidTarget(client, currentTarget)) {
            currentTarget = client.world.getPlayers().stream()
                    .filter(p -> isValidTarget(client, p))
                    .min(Comparator.comparingDouble(p -> client.player.distanceTo(p)))
                    .orElse(null);
        }

        if (currentTarget == null) return;

        Box b = currentTarget.getBoundingBox();
        Vec3d c = client.player.getEyePos();

        double predX = predMode ? (currentTarget.getX() - currentTarget.prevX) * 1.8 : 0;
        double predY = predMode ? (currentTarget.getY() - currentTarget.prevY) * 1.8 : 0;
        double predZ = predMode ? (currentTarget.getZ() - currentTarget.prevZ) * 1.8 : 0;

        double tX, tY, tZ;
        if (aimMode == 0) {
            tX = MathHelper.clamp(c.x, b.minX, b.maxX) + predX;
            tY = MathHelper.clamp(c.y, b.minY, b.maxY) + predY;
            tZ = MathHelper.clamp(c.z, b.minZ, b.maxZ) + predZ;
        } else if (aimMode == 1) {
            tX = (b.minX + b.maxX) / 2 + predX;
            tY = MathHelper.clamp(c.y, b.minY, b.maxY) + predY;
            tZ = (b.minZ + b.maxZ) / 2 + predZ;
        } else if (aimMode == 2) {
            tX = MathHelper.clamp(c.x, b.minX, b.maxX) + predX;
            tY = (b.minY + b.maxY) / 2 + predY;
            tZ = MathHelper.clamp(c.z, b.minZ, b.maxZ) + predZ;
        } else {
            tX = (b.minX + b.maxX) / 2 + predX;
            tY = (b.minY + b.maxY) / 2 + predY;
            tZ = (b.minZ + b.maxZ) / 2 + predZ;
        }

        double dX = tX - c.x;
        double dY = tY - c.y;
        double dZ = tZ - c.z;
        float iY = MathHelper.wrapDegrees((float) Math.toDegrees(Math.atan2(dZ, dX)) - 90.0f);
        float iP = MathHelper.wrapDegrees((float) -Math.toDegrees(Math.atan2(dY, Math.sqrt(dX * dX + dZ * dZ))));
        float curY = client.player.getYaw();
        float curP = client.player.getPitch();
        double diffY = MathHelper.wrapDegrees(iY - curY);
        double diffP = MathHelper.wrapDegrees(iP - curP);

        if (Math.abs(diffY) < 0.05 && Math.abs(diffP) < 0.05) return;

        float nextY, nextP;
        if (hvhMode) {
            nextY = iY;
            nextP = iP;
        } else {
            double speedMod = syncRate * 0.25;
            nextY = (float) (curY + diffY * speedMod);
            nextP = (float) (curP + diffP * speedMod);
        }

        float sens = client.options.getMouseSensitivity().getValue().floatValue();
        float f = sens * 0.6f + 0.2f;
        float gcd = f * f * f * 1.2f;
        float deltaY = nextY - curY;
        float deltaP = nextP - curP;
        deltaY = Math.round(deltaY / gcd) * gcd;
        deltaP = Math.round(deltaP / gcd) * gcd;

        client.player.setYaw(curY + deltaY);
        client.player.setPitch(MathHelper.clamp(curP + deltaP, -90f, 90f));
    }
}