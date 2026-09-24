Rules shows the hard filter, the scoring weights and the thresholds behind every number on the shortlist. The rail on the left lays out the pipeline stages in their five phases, **Read**, **Sort**, **Understand**, **Judge** and **Hand over**, and marks every **AI step** where a model takes part.

<!-- screenshot: rules-stage -->

Pick a stage and the right side shows what decides it: the settings it reads, grouped by file; for the hard filter its knockouts and what each holds back; for scoring the weights, penalties, bands and topics; and for every AI step the prompt exactly as the model receives it. The numbers in the rail are what the last run left at that stage.

The screen is read-only: it shows the configuration as it stands, and changes are made in the configuration itself. **Tip:** keys listed under **Read by nothing** are present in the configuration but used by no stage, which usually means a typo.
