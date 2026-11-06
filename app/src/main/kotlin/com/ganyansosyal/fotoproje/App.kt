package com.ganyansosyal.fotoproje

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Downloads.load()
        FavIcons.load()
        FavIcons.ensure(HomeHtml.hosts())
    }
}
