import { Button } from "@/components/ui/button" 



const IMAGES = [

  "https://cdn.21st.dev/assets/mirror/b4/b489547220e05476a6e9252ee770ceaefcfc04983b0c588116335d048ce1e2c7.jpg",

  "https://cdn.21st.dev/assets/mirror/19/195fc68ab1924d31b9603423fec192334039d7aca6537f284f280ac18b7fd579.jpg",

  "https://cdn.21st.dev/assets/mirror/5e/5e6145c62a3957eaff5285a8fd9aeaad17459c835ae6792f6ce48df1b7c093c4.jpg",

  "https://cdn.21st.dev/assets/mirror/44/44fd2efacd2545fadbf51b1fde849b93c0f1df3bb63e51b3f58989765a0d301f.jpg",

]



export const AboutDemo = () => {

  return (

    <section>

      <div className="mx-auto grid w-full max-w-6xl grid-cols-1 items-center gap-8 px-8 py-12 md:grid-cols-2">

        <ContainerStagger>

          <ContainerAnimated className="mb-4 block text-xs font-medium text-rose-500 md:text-sm">

            Innovate & Grow

          </ContainerAnimated>

          <ContainerAnimated className="text-4xl font-semibold md:text-[2.4rem] tracking-tight">

            Scale Your Business Through Innovation

          </ContainerAnimated>

          <ContainerAnimated className="my-4 text-base text-slate-700 md:my-6 md:text-lg">

            Transform your startup&apos;s potential through innovative solutions

            and strategic growth. We help businesses adapt, evolve, and thrive

            in today&apos;s competitive marketplace.

          </ContainerAnimated>

          <ContainerAnimated>

            <Button className=" bg-rose-500 ">Start Scaling Today</Button>

          </ContainerAnimated>

        </ContainerStagger>



        <GalleryGrid>

          {IMAGES.map((imageUrl, index) => (

            <GalleryGridCell index={index} key={index}>

              <img

                className="size-full object-cover object-center"

                width="100%"

                height="100%"

                src={imageUrl}

                alt=""

              />

            </GalleryGridCell>

          ))}

        </GalleryGrid>

      </div>

    </section>

  )

}