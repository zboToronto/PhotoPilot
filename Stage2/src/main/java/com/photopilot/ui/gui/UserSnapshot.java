package com.photopilot.ui.gui;

import com.photopilot.application.ProfileManager;
import com.photopilot.common.Result;
import com.photopilot.model.LocalProfile;
import java.util.*;

/** One consistent read of the local users for a view; call it off the FX thread. */
record UserSnapshot(List<LocalProfile> users, Optional<LocalProfile> active, Map<UUID, Optional<byte[]>> avatars) {
    static Result<UserSnapshot> load(ProfileManager profiles) {
        var loaded = profiles.listProfiles(); if (!loaded.isSuccess()) return Result.failure(loaded);
        var images = new HashMap<UUID, Optional<byte[]>>();
        for (var user : loaded.value().orElseThrow()) images.put(user.id(), profiles.avatar(user.id()).value().orElse(Optional.empty()));
        return Result.success(new UserSnapshot(loaded.value().orElseThrow(), profiles.activeProfile().value(), images));
    }
}
