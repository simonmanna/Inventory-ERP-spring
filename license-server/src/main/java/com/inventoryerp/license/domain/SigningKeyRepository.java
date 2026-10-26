package com.inventoryerp.license.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SigningKeyRepository extends JpaRepository<SigningKey, String> {

    /** The one key that signs. */
    Optional<SigningKey> findByRetiredAtIsNull();

    Optional<SigningKey> findByKid(String kid);

    List<SigningKey> findAllByOrderByActiveFromDesc();
}
