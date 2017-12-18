package fk.prof.userapi.cache;

import fk.prof.aggregation.AggregatedProfileNamingStrategy;

/**
 * Created by gaurav.ashok on 26/06/17.
 */
public class ProfileLoadInProgressException extends Exception {

    private final AggregatedProfileNamingStrategy profileName;

    public ProfileLoadInProgressException(AggregatedProfileNamingStrategy profileName) {
        super("Loading in progress for file: " + profileName.getFileName(0));
        this.profileName = profileName;
    }

    public AggregatedProfileNamingStrategy getProfileName() {
        return profileName;
    }
}
