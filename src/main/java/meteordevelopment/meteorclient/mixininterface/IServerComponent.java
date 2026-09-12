package meteordevelopment.meteorclient.mixininterface;

/** Implemented by text content that may have arrived from a remote server. */
public interface IServerComponent {
    void ember$markFromServer();

    boolean ember$isFromServer();
}
