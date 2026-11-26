package com.hai.selldonutsmp.modules;

import com.hai.selldonutsmp.util.Money;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Het do an -> /ah steak, quet toi da 3 trang (bam mui ten o 54 / index 53),
 * chon stack 64 steak re nhat, bam mua, tu mua lai neu gap "This item was already bought".
 */
public class FoodBuyer {
    private enum State { IDLE, WAIT_AH, WAIT_ITEMS, WAIT_NEXT_PAGE, WAIT_CONFIRM, WAIT_DONE }

    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static final int SCAN_LIMIT = 45;
    private static final int NEXT_PAGE_SLOT = 53; // O thu 54 (index 53): mui ten sang trang tiep theo
    private static final int MAX_PAGES = 3;
    private static final int TARGET_TOTAL_STEAK = 64;

    private final Setting<Boolean> enabled;
    private final Setting<String> command;
    private final Setting<Integer> amount;
    private final Setting<Double> maxPrice;
    private final Setting<String> priceRegex;
    private final Setting<Integer> retrySeconds;
    private final Setting<Boolean> debug;

    private State state = State.IDLE;
    private int timer = 0;
    private int cooldown = 0;
    private int beforeCount = 0;
    private int currentPage = 1;
    private int expectedSyncId = -1;
    private int listingSyncId = -1;
    private int listingSlot = -1;
    private double listingPrice = 0;
    private int retryAttempts = 0;
    private String lastRegex = null;
    private Pattern lastPattern = null;

    public FoodBuyer(SettingGroup g) {
        enabled = g.add(new BoolSetting.Builder()
            .name("tu-mua-steak-khi-het-do-an")
            .description("Khi tay phu trong va khong con thuc an trong tui do thi tu /ah, mua stack 64 steak re nhat.")
            .defaultValue(true)
            .build()
        );
        command = g.add(new StringSetting.Builder()
            .name("lenh-tim-steak")
            .description("Lenh mo cho dau gia tim steak.")
            .defaultValue("/ah steak")
            .build()
        );
        amount = g.add(new IntSetting.Builder()
            .name("so-steak-moi-stack")
            .description("Chi mua phan dang ban dung so luong steak nay (mac dinh 64).")
            .defaultValue(64)
            .min(1)
            .sliderMax(64)
            .build()
        );
        maxPrice = g.add(new DoubleSetting.Builder()
            .name("gia-toi-da")
            .description("Khong mua neu stack re nhat dat hon muc nay (0 = khong gioi han).")
            .defaultValue(1000000)
            .min(0)
            .sliderMax(10000000)
            .build()
        );
        priceRegex = g.add(new StringSetting.Builder()
            .name("regex-doc-gia")
            .description("Regex doc gia trong mo ta (lore) cua mon do. Nhom so 1 phai la so tien.")
            .defaultValue("(?i)\\$\\s*([\\d,]+(?:\\.\\d+)?[kmbt]?)(?![a-z])")
            .build()
        );
        retrySeconds = g.add(new IntSetting.Builder()
            .name("thu-lai-sau-giay")
            .description("Neu mua that bai thi doi bao nhieu giay roi moi thu lai.")
            .defaultValue(120)
            .min(10)
            .sliderMax(600)
            .build()
        );
        debug = g.add(new BoolSetting.Builder()
            .name("go-loi-mua-do-an")
            .description("In ra cac buoc mua steak.")
            .defaultValue(false)
            .build()
        );
    }

    public boolean isBusy() {
        return state != State.IDLE;
    }

    public void reset() {
        state = State.IDLE;
        currentPage = 1;
        retryAttempts = 0;
    }

    public void onChatMessage(String rawMessage) {
        if (state == State.IDLE || rawMessage == null) return;
        if (!rawMessage.toLowerCase().contains("already bought")) return;
        if (countSteak() >= TARGET_TOTAL_STEAK) return;

        if (++retryAttempts > 15) {
            fail("Qua nhieu lan bi mua truoc (This item was already bought).");
            return;
        }

        if (debug.get()) ChatUtils.info("Mon da bi mua truoc, quet va mua lai (lan " + retryAttempts + ")...");
        if (containerScreen() != null) {
            state = State.WAIT_ITEMS;
            timer = 6;
        } else {
            openAhNow();
        }
    }

    public void request() {
        if (!enabled.get() || state != State.IDLE || cooldown > 0) return;
        if (mc.player == null || mc.currentScreen != null) return;
        if (countSteak() >= TARGET_TOTAL_STEAK) return;

        AutoSellModule sell = Modules.get().get(AutoSellModule.class);
        if (sell != null && sell.isBusy()) return;
        AutoPayModule pay = Modules.get().get(AutoPayModule.class);
        if (pay != null && pay.isBusy()) return;

        beforeCount = countSteak();
        retryAttempts = 0;
        openAhNow();
    }

    private void openAhNow() {
        String cmd = command.get().trim();
        if (!cmd.startsWith("/")) cmd = "/" + cmd;
        ChatUtils.sendPlayerMsg(cmd);
        ChatUtils.info("Dang mo /ah tim stack " + amount.get() + " steak...");
        currentPage = 1;
        state = State.WAIT_AH;
        timer = 100;
    }

    public void tick() {
        if (cooldown > 0) cooldown--;
        if (mc.player == null || mc.interactionManager == null) return;

        switch (state) {
            case IDLE -> {}
            case WAIT_AH -> {
                GenericContainerScreen s = containerScreen();
                if (s != null) {
                    expectedSyncId = s.getScreenHandler().syncId;
                    state = State.WAIT_ITEMS;
                    timer = 12;
                } else if (--timer <= 0) {
                    fail("Khong mo duoc giao dien /ah.");
                }
            }
            case WAIT_ITEMS -> {
                if (--timer <= 0) scanCurrentPage();
            }
            case WAIT_NEXT_PAGE -> {
                GenericContainerScreen s = containerScreen();
                if (s == null) {
                    fail("Giao dien /ah dong khi chuyen trang.");
                    return;
                }
                if (s.getScreenHandler().syncId != expectedSyncId || --timer <= 0) {
                    expectedSyncId = s.getScreenHandler().syncId;
                    state = State.WAIT_ITEMS;
                    timer = 10;
                }
            }
            case WAIT_CONFIRM -> waitConfirm();
            case WAIT_DONE -> {
                if (--timer <= 0) done();
            }
        }
    }

    private void scanCurrentPage() {
        GenericContainerScreen screen = containerScreen();
        if (screen == null) {
            fail("Giao dien /ah da dong.");
            return;
        }
        GenericContainerScreenHandler h = screen.getScreenHandler();
        int limit = Math.min(SCAN_LIMIT, h.getRows() * 9);

        int bestSlot = -1;
        double best = Double.MAX_VALUE;
        int candidates = 0;

        for (int i = 0; i < limit; i++) {
            ItemStack st = h.getSlot(i).getStack();
            if (!st.isOf(Items.COOKED_BEEF) || st.getCount() != amount.get()) continue;

            double price = readPrice(st);
            if (price < 0) continue;
            candidates++;
            if (price < best) {
                best = price;
                bestSlot = i;
            }
        }

        if (debug.get()) {
            ChatUtils.info("Trang " + currentPage + ": thay " + candidates + " stack " + amount.get() + " steak.");
        }

        if (bestSlot < 0) {
            if (currentPage < MAX_PAGES && h.getRows() * 9 > NEXT_PAGE_SLOT) {
                currentPage++;
                click(h, NEXT_PAGE_SLOT);
                state = State.WAIT_NEXT_PAGE;
                timer = 15;
                return;
            }
            fail("Khong thay stack " + amount.get() + " steak nao trong " + MAX_PAGES + " trang dau cua /ah.");
            return;
        }

        if (maxPrice.get() > 0 && best > maxPrice.get()) {
            fail("Stack steak re nhat co gia " + Money.format(best) + ", cao hon gia-toi-da " + Money.format(maxPrice.get()) + ".");
            return;
        }

        ChatUtils.info("Trang " + currentPage + ": mua stack " + amount.get() + " steak gia " + Money.format(best) + ".");
        click(h, bestSlot);
        listingSyncId = h.syncId;
        listingSlot = bestSlot;
        listingPrice = best;
        state = State.WAIT_CONFIRM;
        timer = 60;
    }

    private void waitConfirm() {
        GenericContainerScreen screen = containerScreen();
        if (screen == null) {
            state = State.WAIT_DONE;
            timer = 10;
            return;
        }

        GenericContainerScreenHandler h = screen.getScreenHandler();
        boolean sameWindow = h.syncId == listingSyncId;
        boolean listingChanged = sameWindow && !isListing(h.getSlot(listingSlot).getStack());

        if (!sameWindow || listingChanged) {
            int btn = findConfirm(h);
            if (btn >= 0) {
                if (debug.get()) ChatUtils.info("Bam nut xac nhan o so " + (btn + 1) + ".");
                click(h, btn);
                state = State.WAIT_DONE;
                timer = 20;
                return;
            }
            if (listingChanged) {
                state = State.WAIT_DONE;
                timer = 10;
                return;
            }
        }

        if (--timer <= 0) fail("Bam vao steak nhung khong thay nut xac nhan mua.");
    }

    private void done() {
        int after = countSteak();
        if (after >= TARGET_TOTAL_STEAK || after > beforeCount) {
            closeScreen();
            ChatUtils.info("Da mua steak (" + after + " cai trong tui, gia " + Money.format(listingPrice) + "), se dua sang tay phu.");
            currentPage = 1;
            retryAttempts = 0;
            cooldown = 60;
            state = State.IDLE;
            return;
        }

        if (containerScreen() != null) {
            state = State.WAIT_ITEMS;
            timer = 8;
        } else {
            openAhNow();
        }
    }

    private void fail(String reason) {
        ChatUtils.warning(reason);
        closeScreen();
        currentPage = 1;
        retryAttempts = 0;
        state = State.IDLE;
        cooldown = retrySeconds.get() * 20;
    }

    private GenericContainerScreen containerScreen() {
        return mc.currentScreen instanceof GenericContainerScreen s ? s : null;
    }

    private void closeScreen() {
        if (mc.player != null && mc.currentScreen instanceof GenericContainerScreen) mc.player.closeHandledScreen();
    }

    private void click(GenericContainerScreenHandler h, int slot) {
        mc.interactionManager.clickSlot(h.syncId, slot, 0, SlotActionType.PICKUP, mc.player);
    }

    private boolean isListing(ItemStack st) {
        return st.isOf(Items.COOKED_BEEF) && st.getCount() == amount.get();
    }

    private int findConfirm(GenericContainerScreenHandler h) {
        int top = h.getRows() * 9;
        for (int i = 0; i < top; i++) {
            ItemStack st = h.getSlot(i).getStack();
            if (st.isEmpty()) continue;
            if (st.isOf(Items.LIME_STAINED_GLASS_PANE) || st.isOf(Items.LIME_CONCRETE) || st.isOf(Items.EMERALD_BLOCK)) return i;
            if (st.getName().getString().toLowerCase().contains("confirm")) return i;
        }
        return -1;
    }

    private double readPrice(ItemStack st) {
        LoreComponent lore = st.get(DataComponentTypes.LORE);
        if (lore == null) return -1;

        double fallback = -1;
        for (Text line : lore.lines()) {
            String t = line.getString();
            Matcher m = pricePattern().matcher(t);
            if (!m.find()) continue;
            double v;
            try {
                v = Money.parse(m.group(1));
            } catch (Exception e) {
                continue;
            }
            String low = t.toLowerCase();
            if (low.contains("price") || low.contains("gia")) return v;
            if (fallback < 0) fallback = v;
        }
        return fallback;
    }

    private Pattern pricePattern() {
        String r = priceRegex.get();
        if (!r.equals(lastRegex)) {
            try {
                lastPattern = Pattern.compile(r);
            } catch (Exception e) {
                ChatUtils.warning("regex-doc-gia khong hop le.");
                lastPattern = Pattern.compile("a^");
            }
            lastRegex = r;
        }
        return lastPattern;
    }

    private int countSteak() {
        if (mc.player == null) return 0;
        int n = 0;
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (s.isOf(Items.COOKED_BEEF)) n += s.getCount();
        }
        return n;
    }
}
