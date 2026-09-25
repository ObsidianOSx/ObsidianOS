// Declarations of the parts of Android that exist on the phone but are not in the public SDK.
// Nothing here is ever packaged: the app depends on this with compileOnly, so at run time the real
// classes from the phone's own framework are used. Keeping them as source rather than reflection
// means the updater reads like ordinary code and the compiler checks the calls.
plugins { id("java-library") }
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
