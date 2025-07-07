package anticope.rejects.modules;

import anticope.rejects.MeteorRejectsAddon;
import com.mojang.brigadier.suggestion.Suggestion;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.RequestCommandCompletionsC2SPacket;
import net.minecraft.network.packet.s2c.play.CommandSuggestionsS2CPacket;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class AntiVanish extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> interval = sgGeneral.add(new IntSetting.Builder()
            .name("interval")
            .description("Vanish check interval.")
            .defaultValue(100)
            .min(0)
            .sliderMax(300)
            .build()
    );

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
            .name("mode")
            .defaultValue(Mode.LeaveMessage)
            .build()
    );

    private final Setting<String> command = sgGeneral.add(new StringSetting.Builder()
            .name("command")
            .description("The completion command to detect player names.")
            .defaultValue("minecraft:msg")
            .visible(() -> mode.get() == Mode.RealJoinMessage)
            .build()
    );

    // Fake lag settings
    private final Setting<Boolean> fakeLag = sgGeneral.add(new BoolSetting.Builder()
            .name("fake-lag")
            .description("Delays outgoing packets to simulate lag.")
            .defaultValue(false)
            .build()
    );

    private final Setting<Integer> lagDelay = sgGeneral.add(new IntSetting.Builder()
            .name("lag-delay")
            .description("Ticks to delay outgoing packets (20 = 1s).")
            .defaultValue(10)
            .min(1)
            .sliderMax(40)
            .visible(fakeLag::get)
            .build()
    );

    // Fake lag queue
    private final Queue<LagPacket> packetQueue = new LinkedList<>();

    private Map<UUID, String> playerCache = new HashMap<>();
    private final List<String> messageCache = new ArrayList<>();

    private final Random random = new Random();
    private final List<Integer> completionIDs = new ArrayList<>();
    private List<String> completionPlayerCache = new ArrayList<>();

    private int timer = 0;

    public AntiVanish() {
        super(MeteorRejectsAddon.CATEGORY, "anti-vanish", "Notifies user when an admin uses /vanish, and optionally simulates lag (fake lag).");
    }

    @Override
    public void onActivate() {
        timer = 0;
        completionIDs.clear();
        messageCache.clear();
        packetQueue.clear();
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList l = theme.verticalList();
        l.add(theme.label("LeaveMessage: If client didn't receive a quit game message (like essentials)."));
        l.add(theme.label("RealJoinMessage: Tell whether the player is really left by using player name completion."));
        l.add(theme.label("Fake Lag: Delay outgoing packets to simulate lag."));
        return l;
    }

    @EventHandler
    private void onPacket(PacketEvent.Send event) {
        // Chỉ fake lag cho các packet gửi đi từ client (có thể mở rộng loại packet nếu cần)
        if (fakeLag.get() && shouldDelayPacket(event.packet)) {
            packetQueue.add(new LagPacket(event.packet, lagDelay.get()));
            event.cancel();
            return;
        }
    }

    private boolean shouldDelayPacket(Packet<?> packet) {
        // Để hiệu quả, chỉ delay một số loại packet quan trọng (di chuyển, swing, lệnh...)
        // Có thể mở rộng danh sách packet cần delay tuỳ ý
        return true; // Delay tất cả, hoặc lọc theo instanceof
    }

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (mode.get() == Mode.RealJoinMessage && event.packet instanceof CommandSuggestionsS2CPacket packet) {
            if (completionIDs.contains(packet.id())) {
                var lastUsernames = completionPlayerCache.stream().toList();

                completionPlayerCache = packet.getSuggestions().getList().stream()
                        .map(Suggestion::getText)
                        .toList();

                if (lastUsernames.isEmpty()) return;

                Predicate<String> joinedOrQuit = playerName -> lastUsernames.contains(playerName) != completionPlayerCache.contains(playerName);

                for (String playerName : completionPlayerCache) {
                    if (Objects.equals(playerName, mc.player.getName().getString())) continue;
                    if (playerName.contains(" ")) continue;
                    if (playerName.length() < 3 || playerName.length() > 16) continue;
                    if (joinedOrQuit.test(playerName)) {
                        info("Player joined: " + playerName);
                    }
                }

                for (String playerName : lastUsernames) {
                    if (Objects.equals(playerName, mc.player.getName().getString())) continue;
                    if (playerName.contains(" ")) continue;
                    if (playerName.length() < 3 || playerName.length() > 16) continue;
                    if (joinedOrQuit.test(playerName)) {
                        info("Player left: " + playerName);
                    }
                }

                completionIDs.remove(Integer.valueOf(packet.id()));
                event.cancel();
            }
        }
    }

    @EventHandler
    private void onReceiveMessage(ReceiveMessageEvent event) {
        messageCache.add(event.getMessage().getString());
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        // Xử lý fake lag: gửi các packet đã đủ delay
        if (fakeLag.get()) {
            int size = packetQueue.size();
            for (int i = 0; i < size; i++) {
                LagPacket lagPacket = packetQueue.poll();
                if (lagPacket == null) continue;
                lagPacket.ticks--;
                if (lagPacket.ticks <= 0) {
                    mc.getNetworkHandler().sendPacket(lagPacket.packet);
                } else {
                    packetQueue.add(lagPacket);
                }
            }
        }
        // Xử lý vanish
        timer++;
        if (timer < interval.get()) return;

        switch (mode.get()) {
            case LeaveMessage -> {
                Map<UUID, String> oldPlayers = Map.copyOf(playerCache);
                playerCache = mc.getNetworkHandler().getPlayerList().stream().collect(Collectors.toMap(e -> e.getProfile().getId(), e -> e.getProfile().getName()));

                for (UUID uuid : oldPlayers.keySet()) {
                    if (playerCache.containsKey(uuid)) continue;
                    String name = oldPlayers.get(uuid);
                    if (name.contains(" ")) continue;
                    if (name.length() < 3 || name.length() > 16) continue;
                    if (messageCache.stream().noneMatch(s -> s.contains(name))) {
                        warning(name + " has gone into vanish.");
                    }
                }
            }
            case RealJoinMessage -> {
                int id = random.nextInt(200);
                completionIDs.add(id);
                mc.getNetworkHandler().sendPacket(new RequestCommandCompletionsC2SPacket(id, command.get() + " "));
            }
        }
        timer = 0;
        messageCache.clear();
    }

    // Helper để lưu packet + số tick delay còn lại
    private static class LagPacket {
        public Packet<?> packet;
        public int ticks;

        public LagPacket(Packet<?> packet, int ticks) {
            this.packet = packet;
            this.ticks = ticks;
        }
    }

    public enum Mode {
        LeaveMessage,
        RealJoinMessage//https://github.com/xtrm-en/meteor-antistaff/blob/main/src/main/java/me/xtrm/meteorclient/antistaff/modules/AntiStaff.java
    }
}