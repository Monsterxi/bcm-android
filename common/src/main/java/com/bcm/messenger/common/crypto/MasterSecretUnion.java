package com.bcm.messenger.common.crypto;

import androidx.annotation.NonNull;

import org.whispersystems.libsignal.util.guava.Optional;

public class MasterSecretUnion {

  private final Optional<MasterSecret>           masterSecret;
  private final Optional<AsymmetricMasterSecret> asymmetricMasterSecret;

  public MasterSecretUnion(@NonNull MasterSecret masterSecret) {
    this.masterSecret           = Optional.of(masterSecret);
    this.asymmetricMasterSecret = Optional.absent();
  }

  public MasterSecretUnion(@NonNull AsymmetricMasterSecret asymmetricMasterSecret) {
    this.masterSecret           = Optional.absent();
    this.asymmetricMasterSecret = Optional.of(asymmetricMasterSecret);
  }

  public Optional<MasterSecret> getMasterSecret() {
    return masterSecret;
  }

  public Optional<AsymmetricMasterSecret> getAsymmetricMasterSecret() {
    return asymmetricMasterSecret;
  }
}
