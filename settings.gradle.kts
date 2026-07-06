rootProject.name = "sdk-kotlin"

// The optional, generated OpenAPI admin client ships as a separate artifact
// (ai.shipeasy:shipeasy-admin-kotlin) so the flags SDK keeps zero new deps.
include(":admin")
