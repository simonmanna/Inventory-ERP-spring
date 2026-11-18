"""Idempotent Superset roles and users for the ERP embed (ADR-S078). Run by bootstrap.sh on every start.

- EmbedViewer: the guest-token role (GUEST_ROLE_NAME). Gamma's read permissions; which dashboard and which rows a
  viewer sees is decided by the guest token, not by this role.
- EmbedIssuer: may only mint guest tokens. The ERP API logs in with this user (SUPERSET_EMBED_USERNAME), so its
  password cannot be used to edit dashboards or read data.
"""
import os

from superset.app import create_app

app = create_app()

with app.app_context():
    from superset import db, security_manager as sm

    gamma = sm.find_role("Gamma")
    viewer = sm.find_role("EmbedViewer") or sm.add_role("EmbedViewer")
    viewer.permissions = list(gamma.permissions)

    issuer = sm.find_role("EmbedIssuer") or sm.add_role("EmbedIssuer")
    wanted = [("can_grant_guest_token", "SecurityRestApi"), ("can_read", "SecurityRestApi")]
    perms = []
    for permission, view in wanted:
        pv = sm.find_permission_view_menu(permission, view) or sm.add_permission_view_menu(permission, view)
        perms.append(pv)
    issuer.permissions = perms
    db.session.commit()

    username = os.environ.get("SUPERSET_EMBED_USERNAME", "")
    password = os.environ.get("SUPERSET_EMBED_PASSWORD", "")
    if username and password:
        user = sm.find_user(username=username)
        if user is None:
            sm.add_user(username, "ERP", "Embedder", f"{username}@erp.invalid", issuer, password=password)
        else:
            user.roles = [issuer]
            sm.reset_password(user.id, password)
        db.session.commit()
    print("Superset roles EmbedViewer and EmbedIssuer are in place")
