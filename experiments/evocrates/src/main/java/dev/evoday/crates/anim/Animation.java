package dev.evoday.crates.anim;

public interface Animation {

    void start();

    // сразу показать итог и убрать всё за собой (выход игрока, закрытие меню, выключение сервера)
    void skip();
}
