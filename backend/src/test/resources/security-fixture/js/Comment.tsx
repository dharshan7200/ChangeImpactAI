import DOMPurify from 'dompurify';

export const Comment = ({ body }: { body: string }) => (
  <div>
    <p dangerouslySetInnerHTML={{ __html: body }} /> {/* expect: cia.js.xss.raw-html */}
    <span>{body}</span> {/* ok */}
  </div>
);
