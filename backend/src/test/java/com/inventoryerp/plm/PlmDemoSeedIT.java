package com.inventoryerp.plm;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.seed.SeedContributor;
import com.inventoryerp.plm.application.PlmDemoSeedContributor;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** ADR-S086: the opt-in PLM demo is off by default and leaves a desk family with open PLM work on a fresh org. */
class PlmDemoSeedIT extends ApiTestSupport {

    @Autowired
    PlmDemoSeedContributor demo;

    long count(String sql, String org) {
        return jdbc.sql(sql).param(org).query(Long.class).single();
    }

    @Test
    @DisplayName("PLM demo seed: off by default; on a fresh organization items, types, tags, an ECR and two ECOs")
    void demoSeed() {
        assertThat(count("SELECT count(*) FROM \"Product\" WHERE \"organizationId\" = ? AND code = 'PLM-DESK'",
                demoOrg)).isZero();

        String code = "PD" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String org = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Organization\" (id, code, name, \"currencyCode\", \"updatedAt\") VALUES (?, ?,"
                + " 'PLM Demo', 'UGX', now())").params(org, code).update();
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, 'Administrator', true, ?, now())").params(role, org,
                Permissions.ALL.toArray(String[]::new)).update();
        createUser(org, "owner-" + code.toLowerCase() + "@plm.test", "Plm@123456", role);
        new TransactionTemplate(txm).executeWithoutResult(s -> {
            for (SeedContributor c : seedContributors) {
                if (c.order() <= 300) {
                    c.seed(org);
                }
            }
        });

        Map<String, Object> out = demo.seedDemo(org);
        assertThat(out.get("skipped")).as(String.valueOf(out)).isNull();

        assertThat(count("SELECT count(*) FROM \"PlmItem\" WHERE \"organizationId\" = ?", org)).isEqualTo(5);
        Map<String, Object> steel = jdbc.sql("SELECT i.\"lifecycleState\", r.code AS replacement FROM \"PlmItem\" i"
                + " JOIN \"Product\" p ON p.id = i.\"productId\" LEFT JOIN \"Product\" r ON r.id ="
                + " i.\"replacementProductId\" WHERE i.\"organizationId\" = ? AND p.code = 'PLM-LEG-ST'").param(org)
                .query().singleRow();
        assertThat(steel).containsEntry("lifecycleState", "phase_out").containsEntry("replacement", "PLM-LEG-AL");
        assertThat(count("SELECT count(*) FROM \"PlmChangeType\" WHERE \"organizationId\" = ?", org)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM \"PlmTag\" WHERE \"organizationId\" = ?", org)).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM \"PlmChangeRequest\" WHERE \"organizationId\" = ?", org)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM \"EngineeringChange\" WHERE \"organizationId\" = ? AND status ="
                + " 'draft'", org)).isEqualTo(2);

        Map<String, Object> bomChange = jdbc.sql("SELECT \"firstArticle\", \"costPolicy\", \"newRevision\","
                + " \"requestId\" IS NOT NULL AS linked FROM \"EngineeringChange\" WHERE id = ?")
                .param(out.get("bomChangeId")).query().singleRow();
        assertThat(bomChange).containsEntry("firstArticle", true).containsEntry("costPolicy", "propose")
                .containsEntry("newRevision", "B").containsEntry("linked", true);
        List<Map<String, Object>> draft = jdbc.sql("SELECT p.code, l.quantity FROM \"BomLine\" l JOIN \"Product\" p ON"
                + " p.id = l.\"componentProductId\" WHERE l.\"bomId\" = ? ORDER BY l.sequence")
                .param(out.get("draftBomId")).query().listOfRows();
        assertThat(draft).extracting(r -> r.get("code")).containsExactly("PLM-TOP", "PLM-LEG-AL", "PLM-SCREW");
        assertThat((BigDecimal) draft.get(1).get("quantity")).isEqualByComparingTo("4");

        Map<String, Object> dataChange = jdbc.sql("SELECT \"changeTypeId\", \"productChanges\"->>'name' AS name"
                + " FROM \"EngineeringChange\" WHERE id = ?").param(out.get("productChangeId")).query().singleRow();
        assertThat(dataChange).containsEntry("changeTypeId", out.get("changeTypeId"))
                .containsEntry("name", "Office desk 120 cm, oak");

        assertThat(demo.seedDemo(org).get("skipped")).isEqualTo(true);
    }
}
