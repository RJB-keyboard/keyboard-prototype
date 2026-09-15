# Tatoeba-derived character language model

`conversion/surface/model.bin` is derived from Japanese sentences contributed
to [Tatoeba](https://tatoeba.org/) by the contributors identified in the
adjacent `TATOEBA-ATTRIBUTION.tsv.zip` file. Each row identifies a source
sentence and its contributor; `\N` denotes a sentence without a current owner.
Sentence pages are `https://tatoeba.org/en/sentences/show/{sentence_id}`;
contributor profiles are `https://tatoeba.org/en/user/profile/{contributor}`.

The source text is available under the Creative Commons Attribution 2.0
France license: https://creativecommons.org/licenses/by/2.0/fr/
Legal code: https://creativecommons.org/licenses/by/2.0/fr/legalcode
This derived model is distributed under the same license. Retain this notice
and the attribution file when redistributing it. No endorsement is implied.

Source export:
https://downloads.tatoeba.org/exports/per_language/jpn/jpn_sentences_detailed.tsv.bz2
Retrieved 2026-09-15. SHA-256:
`22507d7f514fc6e600958c5e97403364af0ae755d53ed01397bce3d83ece1510`

Changes: Japanese text was normalized with Unicode NFKC, limited to 3–256
UTF-16 code units, deduplicated, and split deterministically by sentence hash.
224,261 sentences were used to count character 1–4-grams; 24,619 sentences
were excluded as a holdout. Rare counts were pruned. The distributed model
contains n-gram counts, not complete sentences. Attribution lists training
sentences only. The model is not trained on keyboard users' input.

The corpus is volunteer-contributed and has not been individually reviewed
by this project. Its limited context and spelling preferences can cause
ranking errors. See the project's conversion documentation for evaluation.
