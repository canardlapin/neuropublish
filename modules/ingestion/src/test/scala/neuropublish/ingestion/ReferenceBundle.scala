package neuropublish.ingestion

/** The reference bundle's assets: (id, file under `reference/assets/`, media type). */
object ReferenceBundle:
  val assets: List[(String, String, String)] = List(
    ("t1", "t1.nii", "application/x-nifti"),
    ("speech-effect", "speech-effect.nii", "application/x-nifti"),
    ("speech-se", "speech-se.nii", "application/x-nifti"),
    ("speech-t", "speech-t.nii", "application/x-nifti"),
    ("speech-z", "speech-z.nii", "application/x-nifti"),
    ("lh-pial", "lh-pial.surf.gii", "application/x-gifti"),
    ("rh-pial", "rh-pial.surf.gii", "application/x-gifti"),
    ("speech-t-lh", "speech-t-lh.func.gii", "application/x-gifti"),
    ("speech-t-rh", "speech-t-rh.func.gii", "application/x-gifti"),
    ("speech-z-lh", "speech-z-lh.func.gii", "application/x-gifti"),
    ("speech-z-rh", "speech-z-rh.func.gii", "application/x-gifti")
  )
  val ids: List[String] = assets.map(_._1)
