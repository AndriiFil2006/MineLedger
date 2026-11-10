package com.mineledger.client.screen;

import java.util.List;
import java.util.function.Consumer;
import com.mineledger.education.AssignmentDirectory;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.network.chat.Component;

/** Same assignment rows and ID callbacks in both navigation routes. */
final class AssignmentRows {
    static void append(LinearLayout body, int width, Font font, List<AssignmentDirectory.Entry> entries,
            Consumer<String> select) {
        for (var entry : entries) {
            text(body, width, font, entry.assignment().title());
            String label = switch (entry.action()) {
                case COMING_SOON -> "Coming Soon";
                case START -> "Start  ·  Available  ·  " + progress(entry);
                case CONTINUE -> "Continue  ·  Available  ·  " + progress(entry);
                case RECHARGE -> "Recharge Practice  ·  " + progress(entry) + "  ·  No extra XP";
            };
            Button action = body.addChild(Button.builder(Component.literal(label),
                    button -> select.accept(entry.assignment().id())).size(width, 20).build());
            action.active = entry.action() != AssignmentDirectory.Action.COMING_SOON;
        }
    }

    private static String progress(AssignmentDirectory.Entry entry) {
        return entry.completedConcepts() + "/" + entry.totalConcepts() + " concepts completed";
    }

    static void text(LinearLayout body, int width, Font font, String message) {
        body.addChild(new MultiLineTextWidget(Component.literal(message)
                .withStyle(style -> style.withColor(0x35383D).withoutShadow()), font).setMaxWidth(width));
    }

    private AssignmentRows() { }
}
