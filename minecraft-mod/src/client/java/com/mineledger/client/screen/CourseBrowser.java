package com.mineledger.client.screen;

import java.util.function.BiConsumer;
import com.mineledger.accounting.mastery.ConceptProgressionService;
import com.mineledger.accounting.mastery.LearningSession;
import com.mineledger.education.CourseCatalog;
import com.mineledger.education.CourseNavigation;
import com.mineledger.education.CourseProgress;
import com.mineledger.education.AssignmentDirectory;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.network.chat.Component;

/** Compact course browser presentation; routing, IDs and aggregation are domain concerns. */
final class CourseBrowser {
    private final CourseCatalog catalog;
    private final CourseNavigation navigation;
    private final AssignmentDirectory directory;

    CourseBrowser(CourseCatalog catalog) {
        this.catalog = catalog;
        navigation = new CourseNavigation(catalog);
        directory = new AssignmentDirectory(catalog);
    }

    boolean back() { return navigation.back(); }

    void populate(LinearLayout body, int width, Font font, LearningSession session, Runnable refresh,
            BiConsumer<ConceptProgressionService, Boolean> launch) {
        CourseProgress progress = new CourseProgress(session);
        if (navigation.course().isEmpty()) {
            text(body, width, font, "Your courses");
            for (var course : catalog.courses()) {
                body.addChild(Button.builder(Component.literal(course.title()), button -> {
                    navigation.openCourse(course.id());
                    refresh.run();
                }).size(width, 20).build());
                text(body, width, font, progress.course(course).assignmentsLabel());
            }
            return;
        }

        var course = navigation.course().orElseThrow();
        body.addChild(Button.builder(Component.literal(navigation.unit().isPresent() ? "Back to Units" : "Back to Courses"), button -> {
            navigation.back();
            refresh.run();
        }).size(160, 20).build());
        text(body, width, font, course.title());
        if (navigation.unit().isEmpty()) {
            text(body, width, font, course.description() + "\n" + progress.course(course).assignmentsLabel());
            for (var unit : course.units()) {
                body.addChild(Button.builder(Component.literal("Unit " + unit.number() + ": " + unit.title()), button -> {
                    navigation.openUnit(unit.id());
                    refresh.run();
                }).size(width, 20).build());
                text(body, width, font, progress.unit(unit).assignmentsLabel());
            }
        } else {
            var unit = navigation.unit().orElseThrow();
            text(body, width, font, "Unit " + unit.number() + ": " + unit.title() + "\n"
                    + unit.description() + "\n" + progress.unit(unit).assignmentsLabel());
            AssignmentRows.append(body, width, font, directory.entries(unit, session),
                    id -> directory.select(id, session).ifPresent(item -> launch.accept(item.service(), item.recharge())));
        }
    }

    private static void text(LinearLayout body, int width, Font font, String text) {
        body.addChild(new MultiLineTextWidget(Component.literal(text)
                .withStyle(style -> style.withColor(0x35383D).withoutShadow()), font).setMaxWidth(width));
    }
}
