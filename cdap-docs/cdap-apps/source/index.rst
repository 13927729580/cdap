.. meta::
    :author: Cask Data, Inc.
    :description: Users' Manual
    :copyright: Copyright © 2015 Cask Data, Inc.

.. _cdap-apps-index:

=================
CDAP Applications
=================

.. rubric:: System Artifacts

CDAP comes packaged with several system artifacts to create two types of applications,
simply by configuring the system artifacts and not writing any code at all:

.. |hydrator| replace:: **Cask Hydrator and ETL (Extract, Transform, and Load) Pipelines**
.. _hydrator: hydrator/index.html

.. |dqa| replace:: **Data Quality Application**
.. _dqa: data-quality/index.html

- |hydrator|_
- |dqa|_

An application created from a configured system artifact following the ETL pattern is
referred to as an *ETL pipeline* or (interchangeably) as an *ETL application*. Similarly, an
application built following the Data Quality pattern is referred to as a *Data Quality
application*.

In the future, a variety of system artifacts will be delivered. The framework is
extensible: users can write their own artifacts if they so chose, and can
manage the lifecycle of their custom applications using CDAP.


.. rubric:: Navigator Integration Application

The Navigator Integration App is an application built by the team at Cask for bridging CDAP Metadata
with Cloudera's data management tool, Navigator. The Navigator Integration App is a CDAP-native application
that uses a real-time flow to fetch the CDAP Metadata and write it to Cloudera Navigator.
