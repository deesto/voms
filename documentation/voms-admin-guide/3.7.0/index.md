---
layout: default
title: VOMS Admin documentation
version: 3.7.0
redirect_from:
  - /documentation/voms-admin-guide/
---

# VOMS Admin documentation

{% include voms-admin-guide-version.liquid %}

The VOMS Admin documentation is composed of the following guides:

- The [VOMS Admin User Guide](user-guide.html), targeted at VO members and
  applicants, i.e. users interacting with VOMS Admin to register at a VO,
  request membership in groups etc.

- The [VOMS Admin VO Administrator Guide](vo-admin-guide.html), targeted at VO
  administrators, i.e. users interacting with VOMS Admin to approve/reject VO
  membership requests and perform other VOMS administrative tasks

{% assign sagv = site.data.docs.voms-admin-guide.versions[page.version].sysadmin_guide_version %}

- The [VOMS System Administrator
  guide]({{site.baseurl}}/documentation/sysadmin-guide/{{sagv}}),
  targeted at VOMS service administrators

- The [VOMS Admin REST APIs documentation](api.html), targeted at
  developers
