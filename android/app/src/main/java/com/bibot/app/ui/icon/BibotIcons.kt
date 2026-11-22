package com.bibot.app.ui.icon

import androidx.annotation.DrawableRes
import com.bibot.app.R

/**
 * Một ngôn ngữ icon duy nhất Bibot: vector hand-drawn tự vẽ (res/drawable/ic_hd_*.xml).
 * Không dùng asset Fluent/Magnific hay Material Icons. Mọi icon chức năng đều lấy
 * id từ object này và tint bằng accent #1976D2 qua Icon().
 */
object BibotIcons {
    // Home / mode
    @DrawableRes val Mic = R.drawable.ic_hd_mic
    @DrawableRes val Book = R.drawable.ic_hd_book
    @DrawableRes val Story = R.drawable.ic_hd_story
    @DrawableRes val FairyTales = R.drawable.ic_hd_fairy
    @DrawableRes val Music = R.drawable.ic_hd_music
    @DrawableRes val Weather = R.drawable.ic_hd_weather
    @DrawableRes val News = R.drawable.ic_hd_news
    @DrawableRes val Settings = R.drawable.ic_hd_settings

    // Settings sections
    @DrawableRes val Globe = R.drawable.ic_hd_globe
    @DrawableRes val Robot = R.drawable.ic_hd_robot
    @DrawableRes val Key = R.drawable.ic_hd_key
    @DrawableRes val Speaker = R.drawable.ic_hd_speaker
    @DrawableRes val Ticket = R.drawable.ic_hd_ticket
    @DrawableRes val Crown = R.drawable.ic_hd_crown
    @DrawableRes val Package = R.drawable.ic_hd_package
    @DrawableRes val Books = R.drawable.ic_hd_books
    @DrawableRes val Bell = R.drawable.ic_hd_bell
    @DrawableRes val Broom = R.drawable.ic_hd_broom
    @DrawableRes val Question = R.drawable.ic_hd_question
    @DrawableRes val Letter = R.drawable.ic_hd_letter
    @DrawableRes val Info = R.drawable.ic_hd_info
}
