package com.photopilot.integration;

import java.util.List;
import com.photopilot.common.Result;
import com.photopilot.model.PlaceCandidate;

/** F09 place-name lookup port: returns every match so the user chooses; never picks one on the user's behalf. */
public interface PlaceLookupProvider {
    Result<List<PlaceCandidate>> search(String name);
}
