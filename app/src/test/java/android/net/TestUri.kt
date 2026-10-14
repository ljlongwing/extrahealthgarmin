@file:Suppress("unused")

package android.net

import android.os.Parcel

/**
 * Test-only Uri instance. Lives in the android.net package so it can reach
 * Uri's package-private constructor — in unit tests every Uri factory
 * (including the EMPTY constant) is stubbed to null, so no other non-null
 * Uri can exist. None of the members are ever called by the code under test;
 * the Uri is only passed through as an opaque token.
 */
class TestUri : Uri() {

    override fun isHierarchical(): Boolean = true

    override fun isRelative(): Boolean = false

    override fun getScheme(): String? = "content"

    override fun getSchemeSpecificPart(): String? = "//test/goodvibes.csv"

    override fun getEncodedSchemeSpecificPart(): String? = "//test/goodvibes.csv"

    override fun getAuthority(): String? = "test"

    override fun getEncodedAuthority(): String? = "test"

    override fun getUserInfo(): String? = null

    override fun getEncodedUserInfo(): String? = null

    override fun getHost(): String? = "test"

    override fun getPort(): Int = -1

    override fun getPath(): String? = "/goodvibes.csv"

    override fun getEncodedPath(): String? = "/goodvibes.csv"

    override fun getQuery(): String? = null

    override fun getEncodedQuery(): String? = null

    override fun getFragment(): String? = null

    override fun getEncodedFragment(): String? = null

    override fun getPathSegments(): MutableList<String> = mutableListOf("goodvibes.csv")

    override fun getLastPathSegment(): String? = "goodvibes.csv"

    override fun toString(): String = "content://test/goodvibes.csv"

    override fun buildUpon(): Builder = Builder()

    override fun writeToParcel(dest: Parcel, flags: Int) {
        // No-op: never called in unit tests
    }

    override fun describeContents(): Int = 0
}
