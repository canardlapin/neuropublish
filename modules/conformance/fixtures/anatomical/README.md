# Anatomical visual-acceptance corpus

This directory supplements the small synthetic protocol fixture with public,
anatomically realistic inputs for viewer and rendition acceptance. It is not a
Neuropublish bundle and it is not evidence that the volume and surfaces are
co-registered.

The samples have deliberately separate spatial identities:

- the volume is the 2 mm brain-extracted T1w image from
  `MNI152NLin2009cAsym`;
- the surfaces are the left and right cohort-42 pial meshes from `dhcpAsym`.

They may be exercised independently to judge anatomical contrast, mesh
complexity, camera placement, canvas clearing, and lifecycle behavior. They
must not be combined into a scientific overlay or used for analysis. Protocol
conformance continues to use `fixtures/reference`; this corpus is a visual
quality gate, not a replacement scientific result.

`corpus.json` is the machine-checked receipt. It pins each unchanged file by
byte length and SHA-256, the exact source repository commit, its TemplateFlow
object URL, spatial identity, license, and intended role. The conformance suite
then opens the real NIfTI and GIFTI files and sends them through the production
rendition encoders. This prevents a realistic filename, an annex pointer, or a
license-only stub from satisfying the gate.

## Attribution and licenses

The files in `assets/` retain their source licenses. Neuropublish's Apache-2.0
license does not relicense them.

### MNI152NLin2009cAsym T1w volume

ICBM 152 Nonlinear Asymmetrical template version 2009c, by V. Fonov,
A. C. Evans, K. Botteron, C. R. Almli, R. C. McKinstry, and D. L. Collins.
The source metadata cites Fonov et al., *NeuroImage* (2011),
<https://doi.org/10.1016/j.neuroimage.2010.07.033>. The required copyright and
permission notice is reproduced in `LICENSE-MNI152NLin2009cAsym.txt`.

Source: <https://github.com/templateflow/tpl-MNI152NLin2009cAsym>

### dHCP asymmetric pial surfaces

Neonatal cortical surface atlas using Multimodal Surface Matching in the
Developing Human Connectome Project (2018), by J. Bozek, A. Makropoulos,
A. Schuh, S. Fitzgibbon, R. Wright, M. F. Glasser, T. S. Coalson,
J. O'Muircheartaigh, J. Hutter, A. N. Price, L. Cordero-Grande, R. P. A.
Teixeira, E. Hughes, N. Tusor, K. P. Baruteau, M. A. Rutherford,
A. D. Edwards, J. V. Hajnal, S. M. Smith, D. Rueckert, M. Jenkinson, and
E. C. Robinson. The source metadata cites Bozek et al., *NeuroImage* (2018),
<https://doi.org/10.1016/j.neuroimage.2018.06.018>.

Licensed under Creative Commons Attribution 4.0 International:
<https://creativecommons.org/licenses/by/4.0/>. The files are redistributed
unchanged; no endorsement by the authors, dHCP, or TemplateFlow is implied.

Source: <https://github.com/templateflow/tpl-dhcpAsym>
